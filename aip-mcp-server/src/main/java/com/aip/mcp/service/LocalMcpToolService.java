package com.aip.mcp.service;

import com.aip.mcp.dto.ToolDescriptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LocalMcpToolService {

    private final Map<String, ToolCallback> toolCallbacks;

    public LocalMcpToolService(@Qualifier("localMcpToolCallbackProvider") ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbacks = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    public List<ToolDescriptor> listTools() {
        return toolCallbacks.values().stream()
                .map(callback -> new ToolDescriptor(callback.getToolDefinition().name(),
                        callback.getToolDefinition().description(),
                        callback.getToolDefinition().inputSchema()))
                .toList();
    }

    public String invoke(String toolName, String arguments) {
        ToolCallback toolCallback = toolCallbacks.get(toolName);
        if (toolCallback == null) {
            throw new IllegalArgumentException("未找到工具: " + toolName);
        }

        String payload = StringUtils.hasText(arguments) ? arguments : "{}";
        return toolCallback.call(payload);
    }
}
