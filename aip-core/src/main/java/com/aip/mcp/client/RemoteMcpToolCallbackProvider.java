package com.aip.mcp.client;

import com.aip.mcp.config.McpProperties;
import com.aip.mcp.dto.ToolDescriptor;
import com.aip.mcp.dto.ToolInvocationRequest;
import com.aip.mcp.dto.ToolInvocationResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;

public class RemoteMcpToolCallbackProvider {

    private final RestClient restClient;

    private final String apiPrefix;

    private volatile ToolCallback[] cachedCallbacks = new ToolCallback[0];

    public RemoteMcpToolCallbackProvider(McpProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getClient().getBaseUrl())
                .build();
        this.apiPrefix = normalizeApiPrefix(properties.getServer().getApiPrefix());
    }

    public ToolCallback[] getToolCallbacks() {
        if (cachedCallbacks.length == 0) {
            refreshTools();
        }
        return cachedCallbacks.clone();
    }

    public synchronized List<ToolDescriptor> refreshTools() {
        List<ToolDescriptor> descriptors = fetchToolDescriptors();
        this.cachedCallbacks = descriptors.stream().map(this::toToolCallback).toArray(ToolCallback[]::new);
        return descriptors;
    }

    private List<ToolDescriptor> fetchToolDescriptors() {
        List<ToolDescriptor> descriptors = restClient.get()
                .uri(apiPrefix + "/tools")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return descriptors == null ? List.of() : descriptors;
    }

    private ToolCallback toToolCallback(ToolDescriptor descriptor) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(descriptor.getName())
                .description(descriptor.getDescription())
                .inputSchema(descriptor.getInputSchema())
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                ToolInvocationResponse response = restClient.post()
                        .uri(apiPrefix + "/tools/{toolName}/invoke", descriptor.getName())
                        .body(new ToolInvocationRequest(toolInput))
                        .retrieve()
                        .body(ToolInvocationResponse.class);

                if (response == null) {
                    throw new IllegalStateException("远程工具调用没有返回结果: " + descriptor.getName());
                }
                return response.getResult();
            }
        };
    }

    private String normalizeApiPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/mcp/server";
        }
        return prefix.startsWith("/") ? prefix : "/" + prefix;
    }
}
