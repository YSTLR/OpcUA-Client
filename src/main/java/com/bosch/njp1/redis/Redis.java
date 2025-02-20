package com.bosch.njp1.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class Redis {

    private final JedisPool jedisPool;

    public Redis(String host, int port, String password, int timeout,int maxTotal,int maxIdle,int minIdle) {
        //初始化连接池
        this.jedisPool = new JedisPool(buildPoolConfig(maxTotal,maxIdle,minIdle), host, port,timeout,password);

    }

    // 配置连接池
    private JedisPoolConfig buildPoolConfig(int maxTotal,int maxIdle,int minIdle) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(maxTotal);          // 最大连接数
        poolConfig.setMaxIdle(maxIdle);            // 最大空闲连接数
        poolConfig.setMinIdle(minIdle);             // 最小空闲连接数
        return poolConfig;
    }

    public String read(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        }
    }

    public void write(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, value);
        }
    }

    public void writeWithExpiry(String key, String value, int seconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, seconds, value);
        }
    }

    public void delete(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        }
    }

    //shutdown
    public void shutdown() throws InterruptedException {
        jedisPool.getResource().flushDB();
    }

}
