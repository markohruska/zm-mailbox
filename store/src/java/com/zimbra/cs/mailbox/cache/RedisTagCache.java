package com.zimbra.cs.mailbox.cache;

import java.util.Collection;
import java.util.Map;

import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.MailItem.UnderlyingData;
import com.zimbra.cs.mailbox.TransactionAware.CachePolicy;
import com.zimbra.cs.mailbox.TransactionAware.ReadPolicy;
import com.zimbra.cs.mailbox.TransactionAware.WritePolicy;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.RedissonClientHolder;
import com.zimbra.cs.mailbox.Tag;
import com.zimbra.cs.mailbox.TagState;
import com.zimbra.cs.mailbox.TransactionCacheTracker;
import com.zimbra.cs.mailbox.redis.RedisBackedMap;
import com.zimbra.cs.mailbox.redis.RedisUtils;

public class RedisTagCache extends RedisSharedStateCache<Tag> implements TagCache {

    private RedisBackedMap<String, Integer> name2Id;

    public RedisTagCache(Mailbox mbox, TransactionCacheTracker cacheTracker) {
        super(mbox, new LocalTagCache(), cacheTracker);
        RedissonClient client = RedissonClientHolder.getInstance().getRedissonClient();
        String name2IdMapName = RedisUtils.createAccountRoutedKey(mbox.getAccountId(), "TAG_NAME2ID");
        RMap<String, Integer> rmap = client.getMap(name2IdMapName);
        name2Id = new RedisBackedMap<>(rmap, cacheTracker, ReadPolicy.ANYTIME, WritePolicy.ANYTIME, true, CachePolicy.THREAD_LOCAL);
    }

    private LocalTagCache getLocalCache() {
        return (LocalTagCache) localCache;
    }

    @Override
    public Tag remove(String tagName) {
        Tag removed = getLocalCache().remove(tagName);
        if (removed != null) {
            remove(removed.getId());
        }
        return removed;
    }

    @Override
    public Tag remove(int tagId) {
        Tag removed = super.remove(tagId);
        if (removed != null ) {
            name2Id.remove(removed.getName().toLowerCase());
        }
        return removed;
    }

    @Override
    public boolean contains(String tagName) {
        return getLocalCache().contains(tagName);
    }

    @Override
    public void put(Tag tag) {
        super.put(tag);
        name2Id.put(tag.getName().toLowerCase(), tag.getId());
    }

    @Override
    protected Tag construct(int tagId, Map<String, Object> map) {
        UnderlyingData ud = mapToUnderlyingData(tagId, map);
        try {
            Tag tag = (Tag) MailItem.constructItem(mbox, ud, true);
            tag.setIsImapVisible(boolVal(map, TagState.F_IMAP_VISIBLE));
            return tag;
        } catch (ServiceException e) {
            ZimbraLog.mailbox.error("unable to reconstruct Tag from Redis cache for id %d", tagId, e);
            return null;
        }
    }

    @Override
    protected Integer getIdForStringKey(String key) {
        return name2Id.get(key);
    }

    @Override
    protected String getMapName(String accountId, int itemId) {
        return RedisUtils.createAccountRoutedKey(accountId, String.format("TAG:%d", itemId));
    }

    @Override
    protected Collection<Integer> getAllIds() {
        return name2Id.values();
    }

    @Override
    public void updateName(String oldName, String newName) {
        oldName = oldName.toLowerCase();
        Integer tagId = name2Id.get(oldName);
        name2Id.remove(oldName);
        name2Id.put(newName.toLowerCase(), tagId);
        getLocalCache().updateName(oldName, newName);
    }
}