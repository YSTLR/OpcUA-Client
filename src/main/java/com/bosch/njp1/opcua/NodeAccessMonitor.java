package com.bosch.njp1.opcua;

import com.bosch.njp1.config.ApplicationConfig;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NodeAccessMonitor {

    private final ApplicationConfig config;
    // 存储标签及其访问计数
    private final ConcurrentHashMap<String, AtomicInteger> tagAccessCount = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Set<String> hotTags = ConcurrentHashMap.newKeySet();
    private final OpcUaSubscribeClientPool subscribeClientsPool;
    private final Set<String> currentSubscriptions = ConcurrentHashMap.newKeySet();
    private static final AtomicLong clientHandleCounter = new AtomicLong(1L);
    private static final ConcurrentLinkedQueue<UInteger> recycledHandles = new ConcurrentLinkedQueue<>();


    public NodeAccessMonitor(ApplicationConfig config, OpcUaSubscribeClientPool subscribeClientsPool) {
        this.config = config;
        this.subscribeClientsPool = subscribeClientsPool;
        scheduler.scheduleAtFixedRate(this::checkHotTags, config.opcUa.client.hotData.hotDataThreshold, config.opcUa.client.hotData.hotDataCheckWindowMinutes, TimeUnit.MINUTES);
    }

    public void recordTagAccess(ApplicationConfig config, String group, String key) {
        String nodeParam = ApplicationUtil.parseNodeParam(String.valueOf(config.opcUa.client.namespace), group, key);
        int count = tagAccessCount.computeIfAbsent(nodeParam, k -> new AtomicInteger(0)).incrementAndGet();
        System.out.println("recordTagAccess " + nodeParam + ": " + count);
    }

    private void checkHotTags() {
        System.out.println("---开始检查热点数据---");
        int hotDataThreshold = config.opcUa.client.hotData.hotDataThreshold;

        // 1. 扫描访问计数，更新热点数据列表
        tagAccessCount.forEach((tag, count) -> {
            if (count.get() >= hotDataThreshold) {
                System.out.println("热点数据：" + tag + " 访问次数：" + count.get() + " ,加入热点数据列表");
                hotTags.add(tag);
            } else {
                System.out.println("热点数据：" + tag + " 访问次数：" + count.get() + " ,移出热点数据列表");
                hotTags.remove(tag);
            }
            // 重置计数器
            count.set(0);
        });
        // 2. 取消不再是热点的数据订阅
        for (String currentTag : currentSubscriptions) {
            if (!hotTags.contains(currentTag)) {
                unsubscribeTag(currentTag);
                currentSubscriptions.remove(currentTag);
            }
        }
        // 3. 订阅新的热点数据
        for (String tag : hotTags) {
            if (!currentSubscriptions.contains(tag)) {
                subscribeTag(tag);
                currentSubscriptions.add(tag);
            }
        }
        // 4. 检查并重置 `clientHandleCounter`（适当调整检查频率）
        if (clientHandleCounter.get() > 10000L) {
            clientHandleCounter.set(1L);
            recycledHandles.clear();
        }
    }

    public boolean isHotTag(String tag) {
        return hotTags.contains(tag);
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
    }

    private static UInteger getRecycledOrNewClientHandle() {
        UInteger handle = recycledHandles.poll();
        return (handle != null) ? handle : UInteger.valueOf(clientHandleCounter.getAndIncrement());
    }

    private static void recycleClientHandle(UInteger handle) {
        recycledHandles.offer(handle);
    }

    private void onDataChanged(UaMonitoredItem item, DataValue value) {
        System.out.println(item.getMonitoredItemId() + "-" + item.getReadValueId() + " Value changed: " + value.getValue().getValue());
    }

    private void subscribeTag(String tag) {
        String ns = String.valueOf(config.opcUa.client.namespace);
        try {
            OpcUaClient client = subscribeClientsPool.getClient(tag);
            UaSubscription subscription = client.getSubscriptionManager().createSubscription(500.0).get();
            ReadValueId readValueId = new ReadValueId(NodeId.parse(ApplicationUtil.parseNodeParam(ns, tag)), AttributeId.Value.uid(), null, null);
            MonitoringParameters parameters = new MonitoringParameters(
                    getRecycledOrNewClientHandle(), 1000.0, null, UInteger.valueOf(1), true);

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
                        System.out.println("Setting value consumer for item: " + item.getReadValueId());
                        item.setValueConsumer(this::onDataChanged);
                    }
            ).thenAccept(items -> {
                items.forEach(monitoredItem -> System.out.println("Item created for nodeId=" + monitoredItem.getReadValueId().getNodeId()));
            }).exceptionally(e -> {
                e.printStackTrace();
                return null;
            });
        } catch (Exception e) {
            e.printStackTrace();
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
            System.out.println("Unsubscribed tag: " + tag);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
