package com.aip.controller;

import com.aip.dto.AgentChatRequest;
import com.aip.dto.AgentChatResponse;
import com.aip.mcp.service.McpAgentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private final McpAgentService mcpAgentService;

    public AgentController(McpAgentService mcpAgentService) {
        this.mcpAgentService = mcpAgentService;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        return mcpAgentService.chat(request.getQuestion(), request.getConversationId());
    }

    @PostMapping(value = "/chat/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentChatResponse chatWithFiles(@RequestPart("question") String question,
                                           @RequestPart(value = "conversationId", required = false) String conversationId,
                                           @RequestPart("files") MultipartFile[] files) {
        return mcpAgentService.chatWithFiles(question, conversationId, files);
    }
}
