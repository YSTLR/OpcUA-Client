package com.bosch.njp1;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

public class OpcUaClientManager {

    private OpcUaClient client;

    public OpcUaClientManager(OpcUaClient client) {
        this.client = client;
    }

    public void subscribeToNode(String nodeId, java.util.function.Consumer<DataValue> onChange) {
        // 实现新增订阅逻辑
    }

    public void unsubscribeFromNode(String nodeId) {
        // 实现取消订阅逻辑
    }
}
