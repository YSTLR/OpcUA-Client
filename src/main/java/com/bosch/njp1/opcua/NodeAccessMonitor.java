package com.bosch.njp1.opcua;

import com.bosch.njp1.config.ApplicationConfig;
import com.bosch.njp1.redis.Redis;
import com.bosch.njp1.util.ApplicationUtil;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NodeAccessMonitor {
    private static final Logger logger = LoggerFactory.getLogger(NodeAccessMonitor.class);
    private final ApplicationConfig config;
    // 存储标签及其访问计数
    private final ConcurrentHashMap<String, AtomicInteger> tagAccessCount = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Set<String> hotTags = ConcurrentHashMap.newKeySet();
    private final OpcUaSubscribeClientPool subscribeClientsPool;
    private final Set<String> currentSubscriptions = ConcurrentHashMap.newKeySet();
    private static final AtomicLong clientHandleCounter = new AtomicLong(1L);
    private static final ConcurrentLinkedQueue<UInteger> recycledHandles = new ConcurrentLinkedQueue<>();
    private final Redis redis;


    public NodeAccessMonitor(ApplicationConfig config, OpcUaSubscribeClientPool subscribeClientsPool, Redis redis) {
        this.config = config;
        this.subscribeClientsPool = subscribeClientsPool;
        this.redis = redis;
        scheduler.scheduleAtFixedRate(this::checkHotTags, config.opcUa.client.hotData.hotDataCheckWindowMinutes, config.opcUa.client.hotData.hotDataCheckWindowMinutes, TimeUnit.MINUTES);
    }

    public void recordTagAccess(String group, String key) {
        int count = tagAccessCount.computeIfAbsent(group + "." + key, k -> new AtomicInteger(0)).incrementAndGet();
        logger.debug("recordTagAccess {}.{}: {}", group, key, count);
    }

    private void checkHotTags() {
        logger.info("======================Starting check hot tags======================");
        int hotDataThreshold = config.opcUa.client.hotData.hotDataThreshold;
        // 扫描访问计数，更新热点数据列表
        tagAccessCount.forEach((tag, count) -> {
            System.out.println("检查tag:"+tag);
            if (count.get() >= hotDataThreshold) {
                logger.info("Tag: {} ,request count: {} ,will insert into hot tag list", tag, count.get());
                hotTags.add(tag);
            } else {
                logger.info("Tag: {} ,request count: {} ,will remove from hot tag list and reset counter", tag, count.get());
                hotTags.remove(tag);
            }
            // 重置计数器
            count.set(0);
        });
        //扫描上一次记录的全部热点数据，不在访问列表中的话也删除
        hotTags.removeIf(tag -> tagAccessCount.isEmpty() || !tagAccessCount.containsKey(tag));
        // 取消不再是热点的数据订阅，并删除redis键值对
        for (String currentTag : currentSubscriptions) {
            if (!hotTags.contains(currentTag)) {
                redis.delete(currentTag);
                logger.info("Removed from Redis: {}", currentTag);
                unsubscribeTag(currentTag);
                logger.info("Removed from Subscribe: {}", currentTag);
                currentSubscriptions.remove(currentTag);
                logger.info("Removed {}", currentTag);
            }
        }
        tagAccessCount.clear();
        // 订阅新的热点数据
        for (String tag : hotTags) {
            if (!currentSubscriptions.contains(tag)) {
                subscribeTag(tag);
                currentSubscriptions.add(tag);
            }
        }
        // 检查并重置
        if (clientHandleCounter.get() > 1000L) {
            logger.debug("Reset counter");
            clientHandleCounter.set(1L);
            recycledHandles.clear();
            tagAccessCount.clear();
            logger.info("Reset counter done");
        }
        logger.info("======================Finished check hot tags======================");
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.MINUTES)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Shut down monitor...");
        for (String tag : currentSubscriptions) {
            unsubscribeTag(tag);
        }
        logger.info("Clear subscribeTag...");
        for(String tag: hotTags){
            redis.delete(tag);
        }
        logger.info("Clear redis...");
    }

    private static UInteger getRecycledOrNewClientHandle() {
        UInteger handle = recycledHandles.poll();
        return (handle != null) ? handle : UInteger.valueOf(clientHandleCounter.getAndIncrement());
    }

    private void onDataChanged(UaMonitoredItem item, DataValue value) {
        logger.info("{}-{} Value changed: {}", item.getMonitoredItemId(), item.getReadValueId(), value.getValue().getValue());
        redis.write(item.getReadValueId().getNodeId().getIdentifier().toString(), value.getValue().getValue().toString());
    }

    private void subscribeTag(String tag) {
        try {
            OpcUaClient client = subscribeClientsPool.getClient(tag);
            UaSubscription subscription = client.getSubscriptionManager().createSubscription(config.opcUa.client.hotData.requestedPublishingIntervalMillis).get();
            ReadValueId readValueId = new ReadValueId(NodeId.parse(ApplicationUtil.parseNodeParam(String.valueOf(config.opcUa.client.namespace), tag)), AttributeId.Value.uid(), null, null);
            MonitoringParameters parameters = new MonitoringParameters(
                    getRecycledOrNewClientHandle(), config.opcUa.client.hotData.samplingIntervalMillis, null, UInteger.valueOf(1), true);

            MonitoredItemCreateRequest request = new MonitoredItemCreateRequest(
                    readValueId,
                    MonitoringMode.Reporting,
                    parameters
            );

            // Handling CompletableFuture returned by createMonitoredItems
            subscription.createMonitoredItems(
                    TimestampsToReturn.Both,
                    Collections.singletonList(request),
                    (item, id) -> {
                        item.setValueConsumer(this::onDataChanged);
                    }
            ).thenAccept(items -> items.forEach(monitoredItem -> logger.info("Subscribed node: {}", monitoredItem.getReadValueId().getNodeId()))).exceptionally(e -> {
                logger.error(e.getMessage());
                logger.error(Arrays.toString(e.getStackTrace()));
                return null;
            });
        } catch (Exception e) {
            logger.error(e.getMessage());
            logger.error(Arrays.toString(e.getStackTrace()));
        }
    }

    private void unsubscribeTag(String tag) {
        // 实现取消订阅逻辑
        try {
            OpcUaClient client = subscribeClientsPool.getClient(tag);
            List<UaSubscription> subscriptions = client.getSubscriptionManager().getSubscriptions();
            for (UaSubscription subscription : subscriptions) {
                client.getSubscriptionManager().deleteSubscription(subscription.getSubscriptionId()).get();
            }
            logger.info("Unsubscribed tag: {}", tag);
        } catch (Exception e) {
            logger.error(e.getMessage());
            logger.error(Arrays.toString(e.getStackTrace()));
        }
    }
}
