package com.aip.mcp.config;

import com.aip.mcp.client.RemoteMcpToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    @Bean
    public RemoteMcpToolCallbackProvider remoteMcpToolCallbackProvider(McpProperties properties) {
        return new RemoteMcpToolCallbackProvider(properties);
    }
}
