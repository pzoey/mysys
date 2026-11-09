package org.dromara.system.dingtalk.client;

import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的钉钉 token 缓存。
 *
 * @author dromara
 */
@Component
public class RedisDingTalkTokenCache implements DingTalkTokenCache {

    /**
     * {@inheritDoc}
     */
    @Override
    public String get(String key) {
        return RedisUtils.getCacheObject(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(String key, String value, Duration ttl) {
        RedisUtils.setCacheObject(key, value, ttl);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(String key) {
        RedisUtils.deleteObject(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean tryLock(String key, String value, Duration ttl) {
        return RedisUtils.setObjectIfAbsent(key, value, ttl);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(String key, String expect) {
        String current = get(key);
        if (StringUtils.equals(expect, current)) {
            delete(key);
        }
    }

}
