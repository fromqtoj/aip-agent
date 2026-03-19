package com.aip.mcp.dto;

public class ToolInvocationResponse {

    private String toolName;

    private String result;

    public ToolInvocationResponse() {
    }

    public ToolInvocationResponse(String toolName, String result) {
        this.toolName = toolName;
        this.result = result;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }
}
