/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011, 2013, 2014 Zimbra, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.mailbox;

import java.util.EmptyStackException;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.zimbra.common.account.ZAttrProvisioning;
import com.zimbra.common.localconfig.DebugConfig;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.mailbox.lock.DebugZLock;
import com.zimbra.cs.mailbox.lock.ZLock;
import com.zimbra.cs.util.ProvisioningUtil;

/**
 * {@link LocalMailboxLock} is a replacement of the implicit monitor lock using {@code synchronized} methods or statements on
 * a mailbox instance. This gives extended capabilities such as timeout and limit on number of threads waiting for a
 * particular mailbox lock. It is no longer legal to synchronize on a mailbox, otherwise an assertion error will be
 * thrown. {@code Mailbox.beginTransaction()}) internally acquires the mailbox lock and it's released by
 * {@code Mailbox.endTransaction()}, so that you don't have to explicitly call {@link #lock()} and {@link #release()}
 * wrapping a mailbox transaction.
 *
 */
public class LocalMailboxLock implements MailboxLock {
    private final ZLock zLock = DebugConfig.debugMailboxLock ? new DebugZLock() : new ZLock();
    private final Stack<Boolean> lockStack = new Stack<Boolean>();
    private Mailbox mbox;

    public LocalMailboxLock(String id, Mailbox mbox) {
        this.mbox = mbox;
    }

    @Override
    public int getHoldCount() {
        return zLock.getReadHoldCount() + zLock.getWriteHoldCount();
    }

    @Override
    public boolean isWriteLockedByCurrentThread() {
        return zLock.isWriteLockedByCurrentThread();
    }

    @Override
    public boolean isUnlocked() {
        return !isWriteLockedByCurrentThread() && zLock.getReadHoldCount() == 0;
    }

    private boolean tryLock(boolean write) throws InterruptedException {
        if (write) {
            return zLock.writeLock().tryLock(0, TimeUnit.SECONDS);
        } else {
            return zLock.readLock().tryLock(0, TimeUnit.SECONDS);
        }
    }

    private boolean tryLockWithTimeout(boolean write) throws InterruptedException {
        if (write) {
            return zLock.writeLock().tryLock(ProvisioningUtil.getServerAttribute(ZAttrProvisioning.A_zimbraMailBoxLockTimeout, 60), TimeUnit.SECONDS);
        } else {
            return zLock.readLock().tryLock(ProvisioningUtil.getServerAttribute(ZAttrProvisioning.A_zimbraMailBoxLockTimeout, 60), TimeUnit.SECONDS);
        }
    }

    private ThreadLocal<Boolean> assertReadLocks = null;

    private synchronized boolean neverReadBeforeWrite(boolean write) {
        //for sanity checking, we keep list of read locks. the first time caller obtains write lock they must not already own read lock
        //states - no lock, read lock only, write lock only
        if (assertReadLocks == null) {
            assertReadLocks = new ThreadLocal<Boolean>();
        }
        if (zLock.getWriteHoldCount() == 0) {
            if (write) {
                Boolean readLock = assertReadLocks.get();
                if (readLock != null) {
                    ZimbraLog.mailbox.error("read lock held before write", new Exception());
                    assert(false);
                }
            } else {
                assertReadLocks.set(true);
            }
        }
        return true;
    }

    private synchronized boolean debugReleaseReadLock() {
        //remove read lock
        if (zLock.getReadHoldCount() == 0) {
            assertReadLocks.remove();
        }
        return true;
    }

    @VisibleForTesting
    int getQueueLength() {
        return zLock.getQueueLength();
    }

    @VisibleForTesting
    boolean hasQueuedThreads() {
        return zLock.hasQueuedThreads();
    }

    @Override
    public void lock() {
        lock(true);
    }

    @Override
    public void lock(boolean write) {
        write = write || mbox.requiresWriteLock();
        ZimbraLog.mailbox.trace("LOCK %s", (write ? "WRITE" : "READ"));
        assert(neverReadBeforeWrite(write));
        try {
            if (tryLock(write)) {
                if (mbox.requiresWriteLock() && !isWriteLockedByCurrentThread()) {
                    //writer finished a purge while we waited
                    promote();
                    return;
                }
                lockStack.push(write);
                return;
            }
            int queueLength = zLock.getQueueLength();
            if (queueLength >= ProvisioningUtil.getServerAttribute(ZAttrProvisioning.A_zimbraMailboxLockMaxWaitingThreads, 15)) {
                // Too many threads are already waiting for the lock, can't let you queued. We don't want to log stack trace
                // here because once requests back up, each new incoming request falls into here, which creates too much
                // noise in the logs. Unless debug switch is enabled
                LockFailedException e = new LockFailedException("too many waiters: " + queueLength);
                if (DebugConfig.debugMailboxLock) {
                    e.logStackTrace();
                }
                throw e;
            }
            // Wait for the lock up to the timeout.
            if (tryLockWithTimeout(write)) {
                if (mbox.requiresWriteLock() && !isWriteLockedByCurrentThread()) {
                    //writer finished a purge while we waited
                    promote();
                    return;
                }
                lockStack.push(write);
                return;
            }
            LockFailedException e = new LockFailedException("timeout");
            e.logStackTrace();
            throw e;
        } catch (InterruptedException e) {
            throw new LockFailedException("interrupted", e);
        } finally {
            assert(!isUnlocked() || debugReleaseReadLock());
        }
    }

    @Override
    public void release() {
        Boolean write = false;
        try {
            write = lockStack.pop();
        } catch (EmptyStackException ese) {
            //should only occur if locked failed; i.e. tryLock() returned error
            //or if call site has unbalanced lock/release
            ZimbraLog.mailbox.trace("release when not locked?");
            assert(getHoldCount() == 0);
            assert(debugReleaseReadLock());
            return;
        }
        //keep release in order so caller doesn't have to manage write/read flag
        ZimbraLog.mailbox.trace("RELEASE %s", (write ? "WRITE" : "READ"));

        if (write) {
            assert(zLock.getWriteHoldCount() > 0);
            zLock.writeLock().unlock();
        } else {
            zLock.readLock().unlock();
            assert(debugReleaseReadLock());
        }
    }

    private void promote() {
        assert(getHoldCount() == zLock.getReadHoldCount());
        int count = zLock.getReadHoldCount();
        for (int i = 0; i < count - 1; i++) {
            release();
        }
        zLock.readLock().unlock();
        assert(debugReleaseReadLock());
        for (int i = 0; i < count; i++) {
            lock(true);
        }
    }

    public final class LockFailedException extends RuntimeException {
        private static final long serialVersionUID = -6899718561860023270L;

        private LockFailedException(String message) {
            super(message);
        }

        private LockFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        private void logStackTrace() {
            StringBuilder out = new StringBuilder("Failed to lock mailbox\n");
            zLock.printStackTrace(out);
            ZimbraLog.mailbox.error(out, this);
        }
    }

}
