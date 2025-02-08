package com.bosch.njp1;

import com.bosch.njp1.config.ApplicationConfig;
import com.bosch.njp1.config.ConfigLoader;
import com.bosch.njp1.opcua.OpcUaClientPool;
import com.bosch.njp1.opcua.OpcUaSubscribeClientPool;
import com.bosch.njp1.redis.Redis;
import com.bosch.njp1.server.ReadServer;


public class ClientApplication {

    public static void main(String[] args) throws Exception {
        ApplicationConfig config = ConfigLoader.getConfig();
        OpcUaClientPool pool= OpcUaClientPool.getInstance(
                config.opcUa.pool.coreSize,
                config.opcUa.pool.maxSize,
                config.opcUa.pool.maxIdleMinutes,
                config.opcUa.pool.cleanupIntervalMinutes,
                config.opcUa.server.endpoint,
                config.opcUa.client.applicationName,
                config.opcUa.client.applicationUri,
                config.opcUa.client.requestTimeoutMillis
        );
        OpcUaSubscribeClientPool opcUaSubscribeClientPool = new OpcUaSubscribeClientPool(
                config.opcUa.pool.subscribeCoreSize,
                config.opcUa.server.endpoint,
                config.opcUa.client.applicationName,
                config.opcUa.client.applicationUri,
                config.opcUa.client.requestTimeoutMillis
        );

        Redis redis = new Redis(
                config.redis.host,
                config.redis.port,
                config.redis.password,
                config.redis.timeoutMillis,
                config.redis.maxTotal,
                config.redis.maxIdle,
                config.redis.minIdle

        );

        ReadServer readServer = new ReadServer(pool, opcUaSubscribeClientPool, redis, config);
        readServer.start();
    }
}