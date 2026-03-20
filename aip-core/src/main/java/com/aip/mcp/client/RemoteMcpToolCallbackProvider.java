package com.aip.mcp.client;

import com.aip.mcp.config.McpProperties;
import com.aip.mcp.dto.ToolDescriptor;
import com.aip.mcp.dto.ToolInvocationRequest;
import com.aip.mcp.dto.ToolInvocationResponse;
import com.aip.trace.AgentTraceContext;
import com.aip.trace.AgentTraceContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RemoteMcpToolCallbackProvider {

    private final RestClient restClient;

    private final String baseUrl;

    private final String apiPrefix;

    private final AgentTraceContextHolder traceContextHolder;

    private volatile ToolCallback[] cachedCallbacks = new ToolCallback[0];

    public RemoteMcpToolCallbackProvider(McpProperties properties,
                                         AgentTraceContextHolder traceContextHolder) {
        this.baseUrl = properties.getClient().getBaseUrl();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.apiPrefix = normalizeApiPrefix(properties.getServer().getApiPrefix());
        this.traceContextHolder = traceContextHolder;
    }

    public void assertServerAvailable() {
        try {
            Map<String, Object> health = restClient.get()
                    .uri(apiPrefix + "/health")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (health == null) {
                throw new IllegalStateException("MCP health 接口返回空结果");
            }
        }
        catch (RuntimeException e) {
            throw new IllegalStateException("MCP 工具服务不可用，请确认 aip-mcp-server 已启动。地址="
                    + baseUrl + apiPrefix + "/health", e);
        }
    }

    public ToolCallback[] getToolCallbacks() {
        if (cachedCallbacks.length == 0) {
            refreshTools();
        }
        return cachedCallbacks.clone();
    }

    public ToolCallback[] getToolCallbacks(List<String> plannedToolNames) {
        ToolCallback[] allCallbacks = getToolCallbacks();
        if (plannedToolNames == null || plannedToolNames.isEmpty()) {
            return allCallbacks;
        }

        Set<String> planned = new LinkedHashSet<>();
        for (String plannedToolName : plannedToolNames) {
            if (StringUtils.hasText(plannedToolName)) {
                planned.add(plannedToolName.trim());
            }
        }
        if (planned.isEmpty()) {
            return allCallbacks;
        }

        ToolCallback[] filtered = java.util.Arrays.stream(allCallbacks)
                .filter(callback -> planned.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

        // 兜底：规划工具名与服务注册名不一致时，避免无工具可用导致流程卡死。
        return filtered.length == 0 ? allCallbacks : filtered;
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
                AgentTraceContext traceContext = traceContextHolder.get();
                long startNanos = System.nanoTime();
                try {
                    ToolInvocationResponse response = restClient.post()
                            .uri(apiPrefix + "/tools/{toolName}/invoke", descriptor.getName())
                            .body(new ToolInvocationRequest(toolInput))
                            .retrieve()
                            .body(ToolInvocationResponse.class);

                    if (response == null) {
                        throw new IllegalStateException("远程工具调用没有返回结果: " + descriptor.getName());
                    }

                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    if (traceContext != null) {
                        traceContext.addToolExecution(descriptor.getName(), durationMs, true, null);
                    }
                    return response.getResult();
                }
                catch (RuntimeException e) {
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    if (traceContext != null) {
                        traceContext.addToolExecution(descriptor.getName(), durationMs, false, abbreviate(e.getMessage()));
                    }
                    throw e;
                }
            }
        };
    }

    private String normalizeApiPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/mcp/server";
        }
        return prefix.startsWith("/") ? prefix : "/" + prefix;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }
}
