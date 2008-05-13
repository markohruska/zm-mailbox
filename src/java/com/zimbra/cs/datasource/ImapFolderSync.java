/*
 * ***** BEGIN LICENSE BLOCK *****
 *
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2007, 2008 Zimbra, Inc.
 *
 * The contents of this file are subject to the Yahoo! Public License
 * Version 1.0 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 *
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.datasource;

import com.zimbra.cs.mailclient.imap.Flags;
import com.zimbra.cs.mailclient.imap.CAtom;
import com.zimbra.cs.mailclient.imap.ImapConnection;
import com.zimbra.cs.mailclient.imap.ResponseHandler;
import com.zimbra.cs.mailclient.imap.ImapResponse;
import com.zimbra.cs.mailclient.imap.MessageData;
import com.zimbra.cs.mailclient.imap.Mailbox;
import com.zimbra.cs.mailclient.imap.ImapData;
import com.zimbra.cs.mailclient.imap.Literal;
import com.zimbra.cs.mailclient.imap.ListData;
import com.zimbra.cs.mailclient.imap.ImapCapabilities;
import com.zimbra.cs.mailclient.CommandFailedException;
import com.zimbra.cs.mailbox.Flag;
import com.zimbra.cs.mailbox.Folder;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.db.DbImapMessage;
import com.zimbra.cs.account.DataSource;
import com.zimbra.cs.mime.ParsedMessage;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.util.Log;

import javax.mail.MessagingException;
import java.io.IOException;
import java.io.File;
import java.util.Set;
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

class ImapFolderSync {
    private final ImapSync imapSync;
    private final ImapConnection connection;
    private final DataSource ds;
    private final com.zimbra.cs.mailbox.Mailbox mailbox;
    private final Statistics stats = new Statistics();
    private Folder folder;
    private ImapFolder tracker;
    private ImapMessageCollection trackedMsgs;
    private Set<Integer> localIds;

    private static final Log LOG = ZimbraLog.datasource;

    private static class Statistics {
        int matched;
        int updated;
        int addedLocally;
        int addedRemotely;
        int deletedRemotely;
        int deletedLocally;
    }

    public ImapFolderSync(ImapSync imapSync, ImapFolder tracker)
        throws ServiceException {
        this.imapSync = imapSync;
        connection = imapSync.getConnection();
        ds = imapSync.getDataSource();
        mailbox = ds.getMailbox();
        this.tracker = tracker;
    }

    // Synchronizes existing remote IMAP folder -> local folder
    public ImapFolder syncFolder(ListData ld, boolean fullSync)
        throws ServiceException, IOException {
        boolean syncMsgs = true;
        if (tracker != null) {
            syncMsgs = checkTrackedFolder(ld);
        } else {
            createLocalFolder(ld);
        }
        if (syncMsgs && isSelectable()) {
            selectImapFolder(ld.getMailbox());
            syncMessages(fullSync);
        }
        return tracker;
    }

    // Synchronize local folder with remote IMAP folder
    public void syncFolder(Folder folder, boolean fullSync)
        throws ServiceException, IOException {
        this.folder = folder;
        if (tracker != null) {
            // Remote IMAP folder deleted, empty local folder
            String remotePath = tracker.getRemotePath();
            info("Remote IMAP folder '%s' was deleted, deleting local folder",
                 remotePath);
            mailbox.delete(null, folder.getId(), folder.getType());
            ds.deleteImapFolder(tracker);
            return;
        }
        // Create and track new remote IMAP folder
        String remotePath = imapSync.getRemotePath(folder);
        createImapFolder(remotePath);
        Mailbox mb = selectImapFolder(remotePath);
        tracker = ds.createImapFolder(folder.getId(), folder.getPath(),
                                      remotePath, mb.getUidValidity());
        syncMessages(fullSync);
    }

    private void createImapFolder(String name) throws IOException {
        debug("Creating IMAP folder '%s'", name);
        try {
            connection.create(name);
        } catch (CommandFailedException e) {
            // Create failed, so check if folder already exists
            info("Attemping to create IMAP folder '%s' which already exists", name);
            if (connection.list("", name).isEmpty()) {
                throw e;
            }
        }
    }
    
    private boolean isSelectable() {
        return tracker != null && tracker.getUidValidity() > 0;
    }

    /*
     * Synchronizes messages between local and remote folder.
     * Get list of all local ids:
     *  - Includes ids for a) existing tracked messages, b) new local messages
     *  - If tracked but no local id then deleted
     */
    private void syncMessages(boolean fullSync)
        throws ServiceException, IOException {
        checkUidValidity();
        trackedMsgs = DbImapMessage.getImapMessages(mailbox, ds, tracker);
        localIds = getLocalIds();
        long lastUid = trackedMsgs.getMaxUid();
        boolean newMsgs = hasNewImapMessages(lastUid);
        // Sync flags if there are changes or fullsync requested
        if (lastUid > 0 && (fullSync || newMsgs || hasLocalChanges())) {
            syncFlags(lastUid);
        }
        // Fetch new messages
        if (newMsgs) {
            fetchNewMessages(lastUid);
        }
        // Remaining local ids are for new or deleted messages
        for (int id : localIds) {
            ImapMessage msg = trackedMsgs.getByItemId(id);
            if (msg != null) {
                deleteMessage(id);
            } else {
                appendMessage(id);
            }
        }
    }

    private boolean hasLocalChanges() {
        for (int id : localIds) {
            if (!trackedMsgs.containsItemId(id)) {
                return true; // Has new local message
            }
        }
        for (ImapMessage msg : trackedMsgs) {
            if (!localIds.contains(msg.getItemId())) {
                return true; // Has deleted local message
            }
        }
        return false;
    }

    private boolean hasNewImapMessages(long lastUid) {
        Mailbox mb = getMailbox();
        long uidNext = mb.getUidNext();
        return mb.getExists() > 0 && (uidNext <= 0 || lastUid + 1 < uidNext);
    }

    private boolean checkTrackedFolder(ListData ld)
        throws ServiceException, IOException {
        // Check if folder has been deleted locally
        try {
            folder = mailbox.getFolderById(null, tracker.getItemId());
        } catch (MailServiceException.NoSuchItemException e) {
            LOG.info("Local folder '%s' was deleted, so deleting IMAP folder" +
                     " '%s'", tracker.getLocalPath(), tracker.getRemotePath());
            connection.delete(tracker.getRemotePath());
            imapSync.getDataSource().deleteImapFolder(tracker);
            return false;
        }
        // Check if folder exists but was renamed locally
        if (!folder.getPath().equals(tracker.getLocalPath())) {
            return renameFolder(ld);
        }
        return true;
    }

    private boolean renameFolder(ListData ld)
        throws ServiceException, IOException {
        String localPath = folder.getPath();
        String remotePath = tracker.getRemotePath();
        String newRemotePath = imapSync.getRemotePath(folder);
        if (newRemotePath != null) {
            // Folder renamed but still under data source root
            info("Folder was renamed (originally '%s')", tracker.getLocalPath());
            info("Renaming IMAP folder from '%s' to '%s'",
                 remotePath, newRemotePath);
            connection.rename(remotePath, newRemotePath);
            tracker.setLocalPath(localPath);
            tracker.setRemotePath(newRemotePath);
            ds.updateImapFolder(tracker);
            return true;
        }
        // Folder was moved outside of the data source root, or
        // folder should no longer be synchronized
        info("Folder was renamed (originally '%s') and moved outside the " +
             "data source root", tracker.getLocalPath());
        ds.deleteImapFolder(tracker);
        // Create new local folder for remote path
        createLocalFolder(ld);
        return true;
    }

    Mailbox selectImapFolder(String path) throws IOException {
        Mailbox mb = connection.getMailbox();
        if (mb != null && mb.getName().equals(path)) {
            return mb;
        }
        LOG.info("Selecting IMAP folder '%s'", path);
        return connection.select(path);
    }
    
    private void createLocalFolder(ListData ld)
        throws ServiceException, IOException {
        String remotePath = ld.getMailbox();
        String localPath = imapSync.getLocalPath(remotePath, ld.getDelimiter());
        long uidValidity = 0;
        if (!ld.getFlags().isNoselect()) {
            uidValidity = selectImapFolder(remotePath).getUidValidity();
        }
        LOG.info("Found new IMAP folder '%s', creating local folder '%s'",
                 remotePath, localPath);
        try {
            folder = mailbox.getFolderByPath(null, localPath);
        } catch (MailServiceException.NoSuchItemException e) {
            // Local folder already exists
            folder = mailbox.createFolder(
                null, localPath, (byte) 0, MailItem.TYPE_UNKNOWN);
        }
        ds.initializedLocalFolder(localPath, false);
        tracker = ds.createImapFolder(
            folder.getId(), localPath, remotePath, uidValidity);
    }
        
    private void deleteMessage(int id) throws ServiceException {
        debug("Deleting local message with item id %d", id);
        try {
            mailbox.delete(null, id, MailItem.TYPE_UNKNOWN);
        } catch (MailServiceException.NoSuchItemException e) {
            // Ignore
        }
        DbImapMessage.deleteImapMessage(mailbox, tracker.getItemId(), id);
        stats.deletedLocally++;
    }

    private void appendMessage(int id) throws ServiceException, IOException {
        debug("Appending new local message with item id %d", id);
        Message msg = mailbox.getMessageById(null, id);
        if (msg != null) {
            Mailbox mb = connection.getMailbox();
            long uid = ImapUtil.appendUid(connection, mb.getName(), msg);
            if (uid <= 0) {
                throw ServiceException.FAILURE(
                    "Cannot determine UID for message with id " + id +
                    " appended to folder '" + folder.getPath() + "'", null);
            }
            DbImapMessage.storeImapMessage(
                mailbox, folder.getId(), uid, id, msg.getFlagBitmask());
            stats.addedRemotely++;
        }
    }

    /*
     * Checks UIDVALIDITY for currently selected folder. If UIDVALIDITY has
     * changed since the last sync, then append new local messages to the
     * remote folder and empty the local folder so that messages will be
     * fetched again when we synchronize.
     */
    private void checkUidValidity() throws ServiceException, IOException {
        Mailbox mb = getMailbox();
        if (mb.getUidValidity() == tracker.getUidValidity()) {
            return;
        }
        String remotePath = tracker.getRemotePath();
        info("Resynchronizing folder because UIDVALIDITY has changed from " +
             "%d to %d", tracker.getUidValidity(), mb.getUidValidity());
        List<Integer> newLocalIds =
            DbImapMessage.getNewLocalMessageIds(mailbox, ds, tracker);
        if (newLocalIds.size() > 0) {
            info("Copying %d messages to remote folder", newLocalIds.size());
            // TODO Handle append failure for individual messages
            for (int id : newLocalIds) {
                Message msg = mailbox.getMessageById(null, id);
                ImapUtil.append(connection, remotePath, msg);
            }
        }
        // Empty local folder so that it will be resynced later
        mailbox.emptyFolder(null, folder.getId(), false);
        tracker.setUidValidity(mb.getUidValidity());
        ds.updateImapFolder(tracker);
        DbImapMessage.deleteImapMessages(mailbox, tracker.getItemId());
    }

    private void syncFlags(long lastUid) throws ServiceException, IOException {
        debug("Syncing message flags");
        List<MessageData> mds =
            ImapUtil.fetch(connection, "1:" + lastUid, "FLAGS");
        List<Long> deletedUids = new ArrayList<Long>();
        for (MessageData md : mds) {
            Long uid = md.getUid();
            ImapMessage trackedMsg = trackedMsgs.getByUid(uid);
            if (trackedMsg == null) {
                info("Ignoring flags for message with uid %d because it is " +
                     "not being tracked", uid);
                continue;
            }
            int msgId = trackedMsg.getItemId();
            if (localIds.contains(msgId)) {
                localIds.remove(msgId);
                try {
                    updateFlags(trackedMsg, md.getFlags());
                    continue;
                } catch (MailServiceException.NoSuchItemException e) {
                    // Message was deleted locally
                }
            }
            deletedUids.add(uid);
        }
        if (!deletedUids.isEmpty()) {
            deleteMsgs(deletedUids);
        }
    }

    private void deleteMsgs(List<Long> uids)
        throws IOException, ServiceException {
        int size = uids.size();
        debug("Deleting and expunging %d IMAP messages", size);
        boolean uidPlus = connection.hasCapability(ImapCapabilities.UIDPLUS);
        for (int i = 0; i < size; i += 16) {
            deleteMsgs(uids.subList(i, Math.min(size - i, 16)), uidPlus);
        }
        // If UIDPLUS not supported, then all we can do is expunge all messages
        // which unfortunately may end up removing some messages we ourselves
        // did not flag for deletion.
        if (!uidPlus) {
            connection.expunge();
        }
    }

    private void deleteMsgs(List<Long> uids, boolean uidPlus)
        throws IOException, ServiceException {
        String seq = ImapData.asSequenceSet(uids);
        debug("Deleting IMAP messages for sequence set: " + seq);
        // Mark remote messages for deletion
        connection.uidStore(seq, "+FLAGS.SILENT", "(\\Deleted)");
        // If UIDPLUS supported, then expunge deleted messages
        if (uidPlus) {
            connection.uidExpunge(seq);
        }
        // Finally, remove local messages
        for (long uid : uids) {
            ImapMessage msg = trackedMsgs.getByUid(uid);
            DbImapMessage.deleteImapMessage(
                mailbox, msg.getItemId(), tracker.getItemId());
            stats.deletedRemotely++;
        }
    }

    // Updates flags for specified message.
    private void updateFlags(ImapMessage msg, Flags flags)
        throws ServiceException, IOException {
        int id = msg.getItemId();
        int localFlags = msg.getFlags();
        int trackedFlags = msg.getTrackedFlags();
        int remoteFlags = FlagsUtil.imapToZimbraFlags(flags);
        int newFlags = mergeFlags(localFlags, trackedFlags, remoteFlags);
        boolean updated = false;
        if (newFlags != localFlags) {
            mailbox.setTags(null, id, MailItem.TYPE_MESSAGE, newFlags,
                            MailItem.TAG_UNCHANGED);
            updated = true;
        }
        if (newFlags != remoteFlags) {
            String uids = String.valueOf(msg.getUid());
            Flags toAdd = FlagsUtil.getFlagsToAdd(flags, newFlags);
            Flags toRemove = FlagsUtil.getFlagsToRemove(flags, newFlags);
            // UID STORE never fails even if message was deleted
            if (!toAdd.isEmpty()) {
                connection.uidStore(uids, "+FLAGS.SILENT", toAdd);
            }
            if (!toRemove.isEmpty()) {
                connection.uidStore(uids, "-FLAGS.SILENT", toRemove);
            }
            updated = true;
        }
        if (newFlags != trackedFlags) {
            DbImapMessage.setFlags(mailbox, id, newFlags);
            updated = true;
        }
        if (updated) {
            stats.updated++;
            if (LOG.isDebugEnabled()) {
                debug("Updated flags for message with uid %d: local=%s, " +
                    "tracked=%s, remote=%s, new=%s", msg.getUid(),
                    Flag.bitmaskToFlags(localFlags),
                    Flag.bitmaskToFlags(trackedFlags),
                    Flag.bitmaskToFlags(remoteFlags),
                    Flag.bitmaskToFlags(newFlags));
            }
        } else {
            stats.matched++;
        }
    }

    // TODO Handle FETCH_SIZE
    private void fetchNewMessages(long lastUid)
        throws ServiceException, IOException {
        debug("Fetching new messages");
        String seq = (lastUid + 1) + ":*";
        connection.uidFetch(seq, "(BODY.PEEK[] FLAGS INTERNALDATE)",
            new ResponseHandler() {
                public boolean handleResponse(ImapResponse res)
                    throws ServiceException, IOException {
                    if (res.getCode() == CAtom.FETCH) {
                        handleFetchResponse((MessageData) res.getData());
                    }
                    return true;
                }
            });
    }

    private Mailbox getMailbox() {
        return connection.getMailbox();
    }
    
    private void handleFetchResponse(MessageData md)
        throws ServiceException, IOException {
        long uid = md.getUid();
        if (trackedMsgs.getByUid(uid) != null) {
            debug("Skipped fetched message with uid %x because it already " +
                  "exists locally", uid);
            return;
        }
        debug("Found new IMAP message with uid %x", uid);
        // TODO Handle no uid messages
        ParsedMessage pm = newParsedMessage(md);
        if (pm != null) {
            int flags = FlagsUtil.imapToZimbraFlags(md.getFlags());
            int msgId = imapSync.addMessage(pm, folder.getId(), flags);
            DbImapMessage.storeImapMessage(
                mailbox, tracker.getItemId(), uid, msgId, flags);
            stats.addedLocally++;
        }
    }

    private ParsedMessage newParsedMessage(MessageData md)
        throws ServiceException, IOException {
        ImapData body = md.getBodySections()[0].getData();
        Date date = md.getInternalDate();
        Long time = date != null ? (Long) date.getTime() : null;
        try {
            boolean indexAttachments = mailbox.attachmentsIndexingEnabled();
            if (body.isLiteral()) {
                File file = ((Literal) body).getFile();
                if (file != null) {
                    return new ParsedMessage(file, time, indexAttachments);
                }
            }
            return new ParsedMessage(body.getBytes(), time, indexAttachments);
        } catch (MessagingException e) {
            error("Skipping fetched message with uid " + md.getUid() +
                  " due to parse error", e);
            return null;
        }
    }

    private Set<Integer> getLocalIds() throws ServiceException {
        Set<Integer> localIds = new HashSet<Integer>();
        int fid = folder.getId();
        for (int id : mailbox.listItemIds(null, MailItem.TYPE_MESSAGE, fid)) {
            localIds.add(id);
        }
        for (int id : mailbox.listItemIds(null, MailItem.TYPE_CHAT, fid)) {
            localIds.add(id);
        }
        return localIds;
    }

    private static int mergeFlags(int localFlags, int trackedFlags,
                                  int remoteFlags) {
        return trackedFlags & (localFlags & remoteFlags) |
              ~trackedFlags & (localFlags | remoteFlags);
    }

    private void debug(String fmt, Object... args) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[" + folder.getPath() + "] " + String.format(fmt, args));
        }
    }

    private void info(String fmt, Object... args) {
        LOG.info("[" + folder.getPath() + "] " + String.format(fmt, args));
    }

    private void error(String msg, Throwable e) {
        LOG.error("[" + folder.getPath() + "] " + msg, e);
    }

}

