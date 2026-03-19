package com.aip.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "aip.mcp")
public class McpProperties {

    private String workspaceRoot = System.getProperty("user.dir");

    private final Command command = new Command();

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public Command getCommand() {
        return command;
    }

    public static class Command {

        private int timeoutSeconds = 30;

        private List<String> allowList = new ArrayList<>();

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public List<String> getAllowList() {
            return allowList;
        }

        public void setAllowList(List<String> allowList) {
            this.allowList = allowList;
        }
    }
}
