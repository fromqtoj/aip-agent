package com.aip.mcp.dto;

public class ToolInvocationRequest {

    private String arguments;

    public ToolInvocationRequest() {
    }

    public ToolInvocationRequest(String arguments) {
        this.arguments = arguments;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }
}
