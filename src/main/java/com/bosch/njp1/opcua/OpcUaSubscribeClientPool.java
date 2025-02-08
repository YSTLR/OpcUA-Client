package com.bosch.njp1.opcua;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class OpcUaSubscribeClientPool {

    private OpcUaSubscribeClientPool obj = null;
    private List<OpcUaClient> subscribeClients;

    private final String applicationName;
    private final String applicationUri;
    private final int requestTimeoutMillis;
    private final String endpointUrl;
    private final int subscribeCoreSize;


    public void clearSubscribe(){
        for(OpcUaClient client: subscribeClients){
            ImmutableList<UaSubscription> subscriptions = client.getSubscriptionManager().getSubscriptions();
            for(UaSubscription uaSubscription: subscriptions){
                try {
                    CompletableFuture<UaSubscription> future = client.getSubscriptionManager().deleteSubscription(uaSubscription.getSubscriptionId());
                    future.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public OpcUaClient getClient(String tag) {
        if (subscribeClients == null || subscribeClients.isEmpty()) {
            throw new IllegalStateException("Subscribe clients list must not be empty.");
        }

        if(null==obj){
            synchronized (OpcUaSubscribeClientPool.class){
                if(null==obj){
                    obj = new OpcUaSubscribeClientPool(subscribeCoreSize, endpointUrl, applicationName, applicationUri, requestTimeoutMillis);
                }
            }
        }
        // Guava  murmur3_128 哈希算法
        int hash = Hashing.murmur3_128().hashString(tag, StandardCharsets.UTF_8).asInt();
        int index = Math.abs(hash % subscribeClients.size()); // 确保索引为非负数

        return subscribeClients.get(index);
    }

    public OpcUaSubscribeClientPool(
            int subscribeCoreSize,
            String endpointUrl,
            String applicationName,
            String applicationUri,
            int requestTimeoutMillis) {


        //指定客户端基础信息
        this.applicationName = applicationName;
        this.applicationUri = applicationUri;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.endpointUrl = endpointUrl;
        this.subscribeCoreSize = subscribeCoreSize;

        //创建专门负责订阅节点的客户端
        subscribeClients = new ArrayList<>();
        while (subscribeClients.size() < subscribeCoreSize) {
            subscribeClients.add(createClient());
        }
        System.out.println("已创建订阅线程池，数量等于 " + subscribeClients.size());
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
            EndpointDescription endpoint = endpoints.stream()
                    .filter(e -> e.getSecurityPolicyUri().equals(SecurityPolicy.None.getUri()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No suitable endpoint found."));

            //构建客户端配置对象
            OpcUaClientConfig config = OpcUaClientConfig.builder()
                    .setApplicationName(LocalizedText.english(applicationName))
                    .setApplicationUri(applicationUri)
                    .setEndpoint(endpoint)
                    .setRequestTimeout(UInteger.valueOf(requestTimeoutMillis))
                    .build();

            //创建并返回新的客户端
            OpcUaClient client = OpcUaClient.create(config);
            client.connect().get();
            return client;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OPC UA client", e);
        }
    }

}
