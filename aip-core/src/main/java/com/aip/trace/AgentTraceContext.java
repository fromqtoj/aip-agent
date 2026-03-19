package com.aip.trace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AgentTraceContext {

    private final String question;

    private final String conversationId;

    private final List<MatchedSkillTrace> matchedSkills;

    private final List<String> plannedMcpTools;

    private final List<McpToolExecution> actualToolExecutions = new ArrayList<>();

    public AgentTraceContext(String question,
                             String conversationId,
                             List<MatchedSkillTrace> matchedSkills,
                             List<String> plannedMcpTools) {
        this.question = question;
        this.conversationId = conversationId;
        this.matchedSkills = List.copyOf(matchedSkills);
        this.plannedMcpTools = List.copyOf(plannedMcpTools);
    }

    public String question() {
        return question;
    }

    public String conversationId() {
        return conversationId;
    }

    public List<MatchedSkillTrace> matchedSkills() {
        return matchedSkills;
    }

    public List<String> plannedMcpTools() {
        return plannedMcpTools;
    }

    public synchronized void addToolExecution(String tool, long durationMs, boolean success) {
        addToolExecution(tool, durationMs, success, null);
    }

    public synchronized void addToolExecution(String tool, long durationMs, boolean success, String error) {
        actualToolExecutions.add(new McpToolExecution(tool, durationMs, success, error));
    }

    public synchronized List<McpToolExecution> actualToolExecutions() {
        return List.copyOf(actualToolExecutions);
    }

    public synchronized List<String> actualMcpTools() {
        Set<String> tools = new LinkedHashSet<>();
        for (McpToolExecution execution : actualToolExecutions) {
            tools.add(execution.tool());
        }
        return List.copyOf(tools);
    }

    public synchronized List<String> missingPlannedTools() {
        Set<String> actualTools = new LinkedHashSet<>(actualMcpTools());
        List<String> missing = new ArrayList<>();
        for (String plannedTool : plannedMcpTools) {
            if (!actualTools.contains(plannedTool)) {
                missing.add(plannedTool);
            }
        }
        return List.copyOf(missing);
    }

    public synchronized List<String> unexpectedActualTools() {
        Set<String> plannedToolsSet = new LinkedHashSet<>(plannedMcpTools);
        List<String> unexpected = new ArrayList<>();
        for (String actualTool : actualMcpTools()) {
            if (!plannedToolsSet.contains(actualTool)) {
                unexpected.add(actualTool);
            }
        }
        return List.copyOf(unexpected);
    }

    public synchronized List<String> plannedAndCalledTools() {
        Set<String> plannedToolsSet = new LinkedHashSet<>(plannedMcpTools);
        List<String> plannedAndCalled = new ArrayList<>();
        for (String actualTool : actualMcpTools()) {
            if (plannedToolsSet.contains(actualTool)) {
                plannedAndCalled.add(actualTool);
            }
        }
        return List.copyOf(plannedAndCalled);
    }

    public synchronized SkillMatching skillMatching() {
        return new SkillMatching(matchedSkills);
    }

    public synchronized ToolPlanning toolPlanning() {
        return new ToolPlanning(plannedMcpTools);
    }

    public synchronized ToolExecution toolExecution() {
        Set<String> plannedToolsSet = new LinkedHashSet<>(plannedMcpTools);
        List<ToolExecutionTrace> tracedExecutions = new ArrayList<>();
        for (McpToolExecution execution : actualToolExecutions) {
            boolean plannedBySkill = plannedToolsSet.contains(execution.tool());
            tracedExecutions.add(new ToolExecutionTrace(
                    execution.tool(),
                    execution.durationMs(),
                    execution.success(),
                    execution.error(),
                    plannedBySkill,
                    plannedBySkill ? "skill_planned" : "agent_direct_unplanned"
            ));
        }
        return new ToolExecution(List.copyOf(tracedExecutions));
    }

    public synchronized PlanActualDiff planActualDiff() {
        return new PlanActualDiff(plannedAndCalledTools(), missingPlannedTools(), unexpectedActualTools());
    }

    public record MatchedSkillTrace(String fileName,
                                    String name,
                                    int priority,
                                    int matchScore,
                                    boolean always,
                                    List<String> matchedKeywords,
                                    String matchReason,
                                    List<String> plannedTools) {
    }

    public record McpToolExecution(String tool, long durationMs, boolean success, String error) {
    }

    public record SkillMatching(List<MatchedSkillTrace> matchedSkills) {
    }

    public record ToolPlanning(List<String> plannedMcpTools) {
    }

    public record ToolExecution(List<ToolExecutionTrace> actualToolExecutions) {
    }

    public record ToolExecutionTrace(String tool,
                                     long durationMs,
                                     boolean success,
                                     String error,
                                     boolean plannedBySkill,
                                     String callSource) {
    }

    public record PlanActualDiff(List<String> plannedAndCalledTools,
                                 List<String> missingPlannedTools,
                                 List<String> unexpectedActualTools) {
    }
}
