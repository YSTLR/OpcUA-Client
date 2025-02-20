package com.bosch.njp1;

import com.bosch.njp1.config.ApplicationConfig;
import com.bosch.njp1.config.ConfigLoader;
import com.bosch.njp1.database.ServerTagCache;
import com.bosch.njp1.opcua.OpcUaClientPool;
import com.bosch.njp1.opcua.OpcUaSubscribeClientPool;
import com.bosch.njp1.redis.Redis;
import com.bosch.njp1.server.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ClientApplication {
    private static final Logger logger = LoggerFactory.getLogger(ClientApplication.class);

    public static void main(String[] args) throws Exception {

        long startTime = System.currentTimeMillis();
        logger.info("Starting OPC-UA Client [Powered by Siting Yan(NjP1/TEF)]");

        ApplicationConfig config = ConfigLoader.getConfig();
        logger.info("Finished: Load configuration source");

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
        logger.info("Finished: Build request clients pool");

        OpcUaSubscribeClientPool opcUaSubscribeClientPool = new OpcUaSubscribeClientPool(
                config.opcUa.pool.subscribeCoreSize,
                config.opcUa.server.endpoint,
                config.opcUa.client.applicationName,
                config.opcUa.client.applicationUri,
                config.opcUa.client.requestTimeoutMillis
        );
        logger.info("Finished: Build subscribe clients pool");

        Redis redis = new Redis(
                config.redis.host,
                config.redis.port,
                config.redis.password,
                config.redis.timeoutMillis,
                config.redis.maxTotal,
                config.redis.maxIdle,
                config.redis.minIdle
        );
        logger.info("Finished: Build Redis client");

        ServerTagCache.init(
                config.database.url,
                config.database.userName,
                config.database.password,
                config.database.initSql,
                redis
        );
        logger.info("Finished: Init Tag Datatype");

        HttpServer httpServer = new HttpServer(pool, opcUaSubscribeClientPool, redis, config);
        logger.info("Finished: Setup OPC Reader Server");

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pool.shutdown();  // 关闭所有直连客户端
                httpServer.shutdownMonitor(); // 关闭热点数据监视器
                opcUaSubscribeClientPool.shutdown(); //关闭订阅数据客户端
                redis.shutdown(); //关闭缓存数据库
            } catch (Exception e) {
                logger.error("Error while shutting down OPC UA client pool", e);
            }finally {
                logger.info("Goodbye!");
            }
        }));

        httpServer.start(startTime);
        logger.info("Finished: Start OPC Reader Server");
    }
}