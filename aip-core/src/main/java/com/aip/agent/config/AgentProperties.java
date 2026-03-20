package com.aip.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "aip.agent")
public class AgentProperties {

    private String active = "mcp";

    private final ClaudeCodeCli claudeCodeCli = new ClaudeCodeCli();

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public ClaudeCodeCli getClaudeCodeCli() {
        return claudeCodeCli;
    }

    public static class ClaudeCodeCli {

        private boolean enabled = false;

        private List<String> commandTemplate = new ArrayList<>(List.of("claude", "-p", "{prompt}"));

        private long timeoutSeconds = 180;

        private String workingDirectory = "";

        private boolean appendPromptWhenMissing = true;

        private Map<String, String> environment = new HashMap<>();

        private String allowedExportRoot = "/Users/qijian/Desktop/爱化身";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getCommandTemplate() {
            return commandTemplate;
        }

        public void setCommandTemplate(List<String> commandTemplate) {
            this.commandTemplate = commandTemplate;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public boolean isAppendPromptWhenMissing() {
            return appendPromptWhenMissing;
        }

        public void setAppendPromptWhenMissing(boolean appendPromptWhenMissing) {
            this.appendPromptWhenMissing = appendPromptWhenMissing;
        }

        public Map<String, String> getEnvironment() {
            return environment;
        }

        public void setEnvironment(Map<String, String> environment) {
            this.environment = environment;
        }

        public String getAllowedExportRoot() {
            return allowedExportRoot;
        }

        public void setAllowedExportRoot(String allowedExportRoot) {
            this.allowedExportRoot = allowedExportRoot;
        }
    }
}
