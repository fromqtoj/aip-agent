package com.aip.agent;

import com.aip.dto.AgentChatResponse;
import org.springframework.web.multipart.MultipartFile;

public interface AgentExecutor {

    String agentType();

    AgentChatResponse chat(String question, String conversationId);

    AgentChatResponse chatWithFiles(String question, String conversationId, MultipartFile[] files);

    default boolean isAvailable() {
        return true;
    }
}
