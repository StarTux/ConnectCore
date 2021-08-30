package com.winthier.connect;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

public final class Redis {
    private Redis() { }

    public static Jedis getJedis() {
        return Connect.instance.jedisPool.getResource();
    }

    public static String set(final String key, final String value, final long seconds) {
        try (Jedis jedis = getJedis()) {
            String result = jedis.set(key, value);
            if (seconds > 0) jedis.expire(key, seconds);
            return result;
        }
    }

    public static String get(final String key) {
        try (Jedis jedis = getJedis()) {
            return jedis.get(key);
        }
    }

    public static long del(final String key) {
        try (Jedis jedis = getJedis()) {
            return jedis.del(key);
        }
    }

    public static long lpush(final String key, final String value, final long seconds) {
        if (seconds > 0) {
            try (Jedis jedis = getJedis(); Transaction transaction = jedis.multi()) {
                var result = transaction.lpush(key, value);
                transaction.expire(key, seconds);
                transaction.exec();
                return result.get();
            }
        } else {
            try (Jedis jedis = Connect.instance.jedisPool.getResource()) {
                return jedis.lpush(key, value);
            }
        }
    }

    public static String brpop(final String key, final double timeout) {
        try (Jedis jedis = getJedis()) {
            var result = jedis.brpop(timeout, key);
            if (result == null) return null;
            return result.getElement();
        }
    }
}
