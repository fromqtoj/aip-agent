package com.aip.agent.service;

import com.aip.agent.AgentExecutor;
import com.aip.agent.config.AgentProperties;
import com.aip.dto.AgentChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AgentRoutingService {

    private final Map<String, AgentExecutor> executors;

    private final AgentProperties agentProperties;

    public AgentRoutingService(List<AgentExecutor> executors, AgentProperties agentProperties) {
        this.executors = indexExecutors(executors);
        this.agentProperties = agentProperties;
    }

    public AgentChatResponse chat(String question, String conversationId) {
        return resolveActiveExecutor().chat(question, conversationId);
    }

    public AgentChatResponse chatWithFiles(String question, String conversationId, MultipartFile[] files) {
        return resolveActiveExecutor().chatWithFiles(question, conversationId, files);
    }

    private Map<String, AgentExecutor> indexExecutors(List<AgentExecutor> agentExecutors) {
        Map<String, AgentExecutor> indexed = new HashMap<>();
        for (AgentExecutor executor : agentExecutors) {
            String key = normalizeType(executor.agentType());
            if (indexed.containsKey(key)) {
                throw new IllegalStateException("发现重复 agentType: " + key);
            }
            indexed.put(key, executor);
        }
        return Map.copyOf(indexed);
    }

    private AgentExecutor resolveActiveExecutor() {
        String activeType = normalizeType(agentProperties.getActive());
        AgentExecutor executor = executors.get(activeType);
        if (executor == null) {
            throw new IllegalStateException("未找到可用 Agent 实现，active=" + activeType
                    + "，可选值=" + executors.keySet());
        }
        if (!executor.isAvailable()) {
            throw new IllegalStateException("Agent 已配置为 " + activeType + "，但当前未启用。"
                    + "请检查 application.yml 中对应的 enabled 配置。");
        }
        return executor;
    }

    private String normalizeType(String type) {
        String normalized = StringUtils.hasText(type)
                ? type.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-")
                : "mcp";

        if ("spring-ai".equals(normalized)
                || "spring-ai-mcp".equals(normalized)
                || "default".equals(normalized)) {
            return "mcp";
        }
        if ("claude".equals(normalized)
                || "claudecode".equals(normalized)
                || "claude-code".equals(normalized)) {
            return "claude-code-cli";
        }
        return normalized;
    }
}
