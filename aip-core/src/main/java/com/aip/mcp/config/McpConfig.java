package com.aip.mcp.config;

import com.aip.mcp.client.RemoteMcpToolCallbackProvider;
import com.aip.trace.AgentTraceContextHolder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    @Bean
    public RemoteMcpToolCallbackProvider remoteMcpToolCallbackProvider(McpProperties properties,
                                                                       AgentTraceContextHolder traceContextHolder) {
        return new RemoteMcpToolCallbackProvider(properties, traceContextHolder);
    }
}
