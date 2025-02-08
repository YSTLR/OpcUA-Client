package com.bosch.njp1.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ApplicationConfig {

    @JsonProperty("opc-ua")
    public OpcUa opcUa;

    @JsonProperty("http-server")
    public HttpServer httpServer;

    @JsonProperty("redis")
    public Redis redis;

    public static class Redis{
        public String host;
        public int port;
        public String password;
        @JsonProperty("timeout-millis")
        public int timeoutMillis;
        @JsonProperty("max-total")
        public int maxTotal;
        @JsonProperty("max-idle")
        public int maxIdle;
        @JsonProperty("min-idle")
        public int minIdle;
    }

    public static class OpcUa {
        public Server server;
        public Client client;
        public Pool pool;

        public static class Server {
            public String endpoint;
            @JsonProperty("security-policy")
            public String securityPolicy;
            public String username;
            public String password;
        }

        public static class Client {
            public int namespace;
            @JsonProperty("application-name")
            public String applicationName;
            @JsonProperty("application-uri")
            public String applicationUri;
            @JsonProperty("request-timeout-millis")
            public int requestTimeoutMillis;
            @JsonProperty("hot-data")
            public HotData hotData;

            public static class HotData{
                @JsonProperty("hot-data-check-window-minutes")
                public int hotDataCheckWindowMinutes;
                @JsonProperty("hot-data-threshold")
                public int hotDataThreshold;
            }
        }

        public static class Pool {
            @JsonProperty("core-size")
            public int coreSize;
            @JsonProperty("max-size")
            public int maxSize;
            @JsonProperty("max-idle-minutes")
            public int maxIdleMinutes;
            @JsonProperty("cleanup-interval-minutes")
            public int cleanupIntervalMinutes;
            @JsonProperty("borrow-timeout-millis")
            public int borrowTimeoutMillis;
            @JsonProperty("subscribe-core-size")
            public int subscribeCoreSize;
        }
    }

    public static class HttpServer {
        public int port;
        @JsonProperty("read-url")
        public String readUrl;
        @JsonProperty("write-url")
        public String writeUrl;
    }
}
