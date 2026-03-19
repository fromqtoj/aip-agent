package com.aip.controller;

import com.aip.mcp.dto.ToolDescriptor;
import com.aip.mcp.dto.ToolInvocationRequest;
import com.aip.mcp.dto.ToolInvocationResponse;
import com.aip.mcp.service.LocalMcpToolService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${aip.mcp.server.api-prefix:/mcp/server}")
public class McpServerController {

    private final LocalMcpToolService localMcpToolService;

    public McpServerController(LocalMcpToolService localMcpToolService) {
        this.localMcpToolService = localMcpToolService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("toolCount", localMcpToolService.listTools().size());
        return body;
    }

    @GetMapping("/tools")
    public List<ToolDescriptor> tools() {
        return localMcpToolService.listTools();
    }

    @PostMapping("/tools/{toolName}/invoke")
    public ToolInvocationResponse invoke(@PathVariable("toolName") String toolName,
                                         @RequestBody(required = false) ToolInvocationRequest request) {
        try {
            String result = localMcpToolService.invoke(toolName, request == null ? "{}" : request.getArguments());
            return new ToolInvocationResponse(toolName, result);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
