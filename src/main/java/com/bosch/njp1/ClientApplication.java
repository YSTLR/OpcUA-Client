package com.bosch.njp1;

import com.bosch.njp1.config.ApplicationConfig;
import com.bosch.njp1.config.ConfigLoader;
import com.bosch.njp1.opcua.OpcUaClientPool;
import com.bosch.njp1.opcua.OpcUaSubscribeClientPool;
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
        ReadServer readServer = new ReadServer(pool, opcUaSubscribeClientPool, config);
        readServer.start();
    }
}