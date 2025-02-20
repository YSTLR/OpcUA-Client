package com.bosch.njp1.opcua;

import com.google.common.hash.Hashing;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;


public class OpcUaClientPool {
    private static final Logger logger = LoggerFactory.getLogger(OpcUaClientPool.class);
    private static OpcUaClientPool obj = null;

    private final String applicationName;
    private final String applicationUri;
    private final int requestTimeoutMillis;
    private final BlockingQueue<IdleClient> pool;
    private final AtomicInteger currentPoolSize;
    private final int corePoolSize;
    private final int maxPoolSize;
    private final int maxIdleTimeMinutes;
    private final int cleanupIntervalMinutes;
    private final String endpointUrl;
    private final ScheduledExecutorService cleanupScheduler;


    private static class IdleClient {
        final OpcUaClient client;
        volatile long lastUsedTime;

        IdleClient(OpcUaClient client) {
            this.client = client;
            this.lastUsedTime = System.currentTimeMillis();
        }
    }

    public static OpcUaClientPool getInstance(int corePoolSize, int maxPoolSize, int maxIdleTimeMinutes, int cleanupIntervalMinutes, String endpointUrl, String applicationName, String applicationUri, int requestTimeoutMillis) {
        if (null == obj) {
            synchronized (OpcUaClientPool.class) {
                if (null == obj) {
                    obj = new OpcUaClientPool(corePoolSize, maxPoolSize, maxIdleTimeMinutes, cleanupIntervalMinutes, endpointUrl, applicationName, applicationUri, requestTimeoutMillis);
                }
            }
        }
        return obj;
    }

    private OpcUaClientPool(int corePoolSize, int maxPoolSize, int maxIdleTimeMinutes, int cleanupIntervalMinutes, String endpointUrl, String applicationName, String applicationUri, int requestTimeoutMillis) {
        //初始化基础参数
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.maxIdleTimeMinutes = maxIdleTimeMinutes;
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;

        //初始化连接池及其大小
        this.pool = new LinkedBlockingQueue<>(maxPoolSize);
        this.currentPoolSize = new AtomicInteger(0);
        this.cleanupScheduler = Executors.newScheduledThreadPool(1);

        //指定客户端基础信息
        this.applicationName = applicationName;
        this.applicationUri = applicationUri;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.endpointUrl = endpointUrl;

        //创建核心连接，至数量等于核心线程数
        while (currentPoolSize.get() < corePoolSize) {
            pool.offer(new IdleClient(createClient()));
            currentPoolSize.incrementAndGet();
        }
        logger.info("Clients core pool of thread has been created, pool size is {} ", currentPoolSize.get());
        // 定期清理空闲客户端
        scheduleIdleClientCleanup();
    }

    private OpcUaClient createClient() {
        try {
            // 使用 DiscoveryClient 获取端点信息
//            System.out.println("endpointUrl: " + endpointUrl);
            List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(endpointUrl).get();
            if (endpoints.isEmpty()) {
                throw new RuntimeException("Endpoint (" + endpointUrl + ") is not available");
            }
            // 选择一个合适的端点（可以按安全策略或其他条件筛选）
            EndpointDescription endpoint = endpoints.stream().filter(e -> e.getSecurityPolicyUri().equals(SecurityPolicy.None.getUri())).findFirst().orElseThrow(() -> new RuntimeException("No suitable endpoint found."));

            //构建客户端配置对象
            OpcUaClientConfig config = OpcUaClientConfig.builder().setApplicationName(LocalizedText.english(applicationName)).setApplicationUri(applicationUri).setEndpoint(endpoint).setRequestTimeout(UInteger.valueOf(requestTimeoutMillis)).build();

            //创建并返回新的客户端
            OpcUaClient client = OpcUaClient.create(config);
            client.connect().get();
            return client;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OPC UA client", e);
        }
    }


    /**
     * 从对象池中获取一个客户端，该客户端会从池中获取，获取不到时会尝试新建一个客户端，若池中客户端数量已达上限则会报错
     *
     * @param borrowTimeout 获取CLient的超时时间，超出该时间则会获取失败
     * @param timeUnit      超时时间的时间单位
     * @return
     * @throws InterruptedException
     */
    public OpcUaClient borrowClient(long borrowTimeout, TimeUnit timeUnit) throws InterruptedException {
        IdleClient idleClient = pool.poll(borrowTimeout, timeUnit);

        if (null == idleClient && currentPoolSize.get() < maxPoolSize) {
            synchronized (this) {
                if (currentPoolSize.get() < maxPoolSize) {
                    idleClient = new IdleClient(createClient());
                    currentPoolSize.incrementAndGet();
                }
            }
        }
        if (null == idleClient) {
            throw new RuntimeException("No available client in pool within " + borrowTimeout + " " + timeUnit + " , pool size is " + currentPoolSize.get());
        }

        // 更新最后使用时间
        idleClient.lastUsedTime = System.currentTimeMillis();
        return idleClient.client;
    }

    public void returnClient(OpcUaClient client) {
        if (client != null) {
            pool.offer(new IdleClient(client));
        }
    }

    private void scheduleIdleClientCleanup() {

        logger.info("Start cleanup pool resource...");
        cleanupScheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();

            pool.removeIf(idleClient -> {
                boolean isIdleTooLong = (currentTime - idleClient.lastUsedTime) > TimeUnit.MINUTES.toMillis(maxIdleTimeMinutes);  // 超过 5 分钟空闲
                boolean canClose = currentPoolSize.get() > corePoolSize;

                if (isIdleTooLong && canClose) {
                    try {
                        idleClient.client.disconnect().get();
                        currentPoolSize.decrementAndGet();
                        logger.info("Closed idle client.");
                    } catch (Exception e) {
                        logger.error(e.getMessage());
                        logger.error(Arrays.toString(e.getStackTrace()));
                    }
                    return true;  // 从池中移除客户端
                }
                return false;
            });
        }, cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);  // 每分钟执行一次清理
    }

    public void shutdown() {
        for (IdleClient idleClient : pool) {
            try {
                idleClient.client.disconnect().get();
            } catch (Exception e) {
                logger.error(e.getMessage());
                logger.error(Arrays.toString(e.getStackTrace()));
            }
        }
        pool.clear();
        currentPoolSize.set(0);
        logger.info("Shut down OPC UA client pool...");
    }
}
