package com.winthier.connect;

import redis.clients.jedis.Jedis;

public final class Redis {
    private Redis() { }

    public static String set(final String key, final String value, final long seconds) {
        try (Jedis jedis = Connect.instance.jedisPool.getResource()) {
            String result = jedis.set(key, value);
            if (seconds > 0) jedis.expire(key, seconds);
            return result;
        }
    }

    public static String get(final String key) {
        try (Jedis jedis = Connect.instance.jedisPool.getResource()) {
            return jedis.get(key);
        }
    }

    public static long del(final String key) {
        try (Jedis jedis = Connect.instance.jedisPool.getResource()) {
            return jedis.del(key);
        }
    }
}
