package com.aip.trace;

import com.aip.skill.AgentSkillService;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AgentTraceLogFormatter {

    private AgentTraceLogFormatter() {
    }

    public static String format(AgentTraceContext context, List<AgentSkillService.SkillCatalogEntry> skillCatalog) {
        return """
                systemSkills:
                %s

                question: %s
                conversationId: %s
                hitSkills: %s
                usedTools: %s
                toolFromSkill: %s
                """.formatted(
                formatSystemSkills(skillCatalog),
                defaultText(context.question(), "-"),
                defaultText(context.conversationId(), "-"),
                formatHitSkills(context.matchedSkills()),
                formatUsedTools(context.actualToolExecutions()),
                formatToolSkillMapping(context)
        ).strip();
    }

    private static String formatSystemSkills(List<AgentSkillService.SkillCatalogEntry> skillCatalog) {
        if (skillCatalog == null || skillCatalog.isEmpty()) {
            return "-";
        }

        StringBuilder builder = new StringBuilder();
        for (AgentSkillService.SkillCatalogEntry skill : skillCatalog) {
            builder.append("- ").append(skill.name())
                    .append(" [").append(skill.fileName()).append("]")
                    .append(" -> ")
                    .append(formatList(skill.plannedTools()))
                    .append("\n");
        }
        return builder.toString().strip();
    }

    private static String formatHitSkills(List<AgentTraceContext.MatchedSkillTrace> skills) {
        if (skills == null || skills.isEmpty()) {
            return "-";
        }
        return skills.stream()
                .map(skill -> "%s -> %s".formatted(skill.name(), formatList(skill.plannedTools())))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String formatUsedTools(List<AgentTraceContext.McpToolExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return "-";
        }
        return executions.stream()
                .map(execution -> "%s(%s,%sms)".formatted(
                        execution.tool(),
                        execution.success() ? "ok" : defaultText(execution.error(), "failed"),
                        execution.durationMs()
                ))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String formatToolSkillMapping(AgentTraceContext context) {
        List<AgentTraceContext.McpToolExecution> executions = context.actualToolExecutions();
        if (executions == null || executions.isEmpty()) {
            return "-";
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (AgentTraceContext.McpToolExecution execution : executions) {
            mapping.putIfAbsent(execution.tool(), findSkillNameForTool(execution.tool(), context.matchedSkills()));
        }
        return mapping.toString();
    }

    private static String findSkillNameForTool(String toolName, List<AgentTraceContext.MatchedSkillTrace> skills) {
        if (skills != null) {
            for (AgentTraceContext.MatchedSkillTrace skill : skills) {
                if (skill.plannedTools() != null && skill.plannedTools().contains(toolName)) {
                    return skill.name();
                }
            }
        }
        return "direct_or_unknown";
    }

    private static String formatList(List<String> values) {
        return values == null || values.isEmpty() ? "-" : values.toString();
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
