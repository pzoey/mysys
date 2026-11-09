package org.dromara.system.dingtalk.client;

import java.time.Duration;

/**
 * 钉钉 access_token 与同步互斥锁的缓存。
 *
 * @author dromara
 */
public interface DingTalkTokenCache {

    /**
     * 读取缓存。
     *
     * @param key 缓存键
     * @return 缓存值，不存在时返回 null
     */
    String get(String key);

    /**
     * 写入带过期时间的缓存。
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   过期时间
     */
    void set(String key, String value, Duration ttl);

    /**
     * 删除缓存。
     *
     * @param key 缓存键
     */
    void delete(String key);

    /**
     * 仅在键不存在时写入，用作同步互斥。
     *
     * @param key   缓存键
     * @param value 持有者标识
     * @param ttl   过期时间
     * @return true 表示抢到锁
     */
    boolean tryLock(String key, String value, Duration ttl);

    /**
     * 仅当值仍是当前持有者时释放锁。
     *
     * @param key    缓存键
     * @param expect 持有者标识
     */
    void unlock(String key, String expect);

}
