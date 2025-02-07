package com.bosch.njp1;

import com.bosch.njp1.service.ClientBuilder;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;


public class ClientApplication {

    public static void main(String[] args) throws Exception {

        OpcUaClient opcua = ClientBuilder.buildClient("opc.tcp://10.177.241.72:49320", "OPCUA", "123456");
        System.out.println(opcua);
    }
}