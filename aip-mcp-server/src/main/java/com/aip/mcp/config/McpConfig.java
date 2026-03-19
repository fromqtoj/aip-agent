package com.aip.mcp.config;

import com.aip.mcp.tool.McpToolHandler;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    @Bean("localMcpToolCallbackProvider")
    public ToolCallbackProvider localMcpToolCallbackProvider(List<McpToolHandler> toolHandlers) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolHandlers.toArray())
                .build();
    }
}
