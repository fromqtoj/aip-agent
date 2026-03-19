package com.aip.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aip.mcp")
public class McpProperties {

    private final Server server = new Server();

    private final Client client = new Client();

    public Server getServer() {
        return server;
    }

    public Client getClient() {
        return client;
    }

    public static class Server {

        private String apiPrefix = "/mcp/server";

        public String getApiPrefix() {
            return apiPrefix;
        }

        public void setApiPrefix(String apiPrefix) {
            this.apiPrefix = apiPrefix;
        }
    }

    public static class Client {

        private String baseUrl = "http://127.0.0.1:10666";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
