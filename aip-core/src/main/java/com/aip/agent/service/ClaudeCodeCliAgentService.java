package com.aip.agent.service;

import com.aip.agent.AgentExecutor;
import com.aip.agent.config.AgentProperties;
import com.aip.dto.AgentChatResponse;
import com.aip.mcp.client.RemoteMcpToolCallbackProvider;
import com.aip.mcp.dto.ToolDescriptor;
import com.aip.skill.AgentSkillService;
import com.aip.trace.AgentTraceContext;
import com.aip.trace.AgentTraceLogFormatter;
import com.aip.trace.AgentTraceContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ClaudeCodeCliAgentService implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCliAgentService.class);

    private static final Logger traceLog = LoggerFactory.getLogger("MCP_TRACE_LOGGER");

    private static final int MAX_FILE_CHAR_COUNT = 12000;

    private static final int MAX_TOOL_ROUNDS = 8;

    private static final Pattern JSON_CODE_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*})\\s*```", Pattern.DOTALL);

    private static final int MAX_TOOL_RESULT_LENGTH = 2000;

    private static final Set<String> TEXT_FILE_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "xml", "html", "htm", "csv", "log",
            "java", "kt", "groovy", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs",
            "sql", "sh", "yml", "yaml", "properties", "conf", "ini"
    );

    private final AgentProperties agentProperties;

    private final AgentSkillService agentSkillService;

    private final RemoteMcpToolCallbackProvider remoteMcpToolCallbackProvider;

    private final AgentTraceContextHolder traceContextHolder;

    private final ObjectMapper objectMapper;

    public ClaudeCodeCliAgentService(AgentProperties agentProperties,
                                     AgentSkillService agentSkillService,
                                     RemoteMcpToolCallbackProvider remoteMcpToolCallbackProvider,
                                     AgentTraceContextHolder traceContextHolder,
                                     ObjectMapper objectMapper) {
        this.agentProperties = agentProperties;
        this.agentSkillService = agentSkillService;
        this.remoteMcpToolCallbackProvider = remoteMcpToolCallbackProvider;
        this.traceContextHolder = traceContextHolder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String agentType() {
        return "claude-code-cli";
    }

    @Override
    public boolean isAvailable() {
        return agentProperties.getClaudeCodeCli().isEnabled();
    }

    @Override
    public AgentChatResponse chat(String question, String conversationId) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }

        String resolvedConversationId = resolveConversationId(conversationId);
        return executeAgent(question.strip(), buildUserContext(question), resolvedConversationId, List.of());
    }

    @Override
    public AgentChatResponse chatWithFiles(String question, String conversationId, MultipartFile[] files) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("files 不能为空");
        }

        String resolvedConversationId = resolveConversationId(conversationId);
        List<String> fileNames = new ArrayList<>();
        String userContext = buildUserContextWithFiles(question, files, fileNames);
        return executeAgent(question.strip(), userContext, resolvedConversationId, fileNames);
    }

    private AgentChatResponse executeAgent(String question,
                                           String userContext,
                                           String conversationId,
                                           List<String> fileNames) {
        AgentSkillService.SkillMatchResult skillMatchResult = agentSkillService.resolveSkillContext(question);
        AgentTraceContext traceContext = new AgentTraceContext(
                question,
                conversationId,
                buildMatchedSkillTraces(skillMatchResult),
                skillMatchResult.plannedMcpTools()
        );
        traceContextHolder.set(traceContext);
        try {
            if (!skillMatchResult.plannedMcpTools().isEmpty()) {
                remoteMcpToolCallbackProvider.assertServerAvailable();
            }
            List<ToolDescriptor> allToolDescriptors = remoteMcpToolCallbackProvider.refreshTools();
            Map<String, ToolCallback> toolCallbacks = indexToolCallbacks(remoteMcpToolCallbackProvider.getToolCallbacks());
            String answer = executeToolAwareCli(userContext, conversationId, skillMatchResult, allToolDescriptors, toolCallbacks);
            return new AgentChatResponse(conversationId, answer, fileNames);
        }
        finally {
            traceLog.info(AgentTraceLogFormatter.format(traceContext, agentSkillService.listSkillCatalog()));
            traceContextHolder.clear();
        }
    }

    private List<AgentTraceContext.MatchedSkillTrace> buildMatchedSkillTraces(AgentSkillService.SkillMatchResult skillMatchResult) {
        return skillMatchResult.matchedSkills().stream()
                .map(skill -> new AgentTraceContext.MatchedSkillTrace(
                        skill.fileName(),
                        skill.name(),
                        skill.priority(),
                        skill.matchScore(),
                        skill.always(),
                        skill.matchedKeywords(),
                        skill.matchReason(),
                        skill.plannedTools()
                ))
                .toList();
    }

    private Map<String, ToolCallback> indexToolCallbacks(ToolCallback[] callbacks) {
        Map<String, ToolCallback> indexed = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            indexed.put(callback.getToolDefinition().name(), callback);
        }
        return Map.copyOf(indexed);
    }

    private String executeToolAwareCli(String userContext,
                                       String conversationId,
                                       AgentSkillService.SkillMatchResult skillMatchResult,
                                       List<ToolDescriptor> allToolDescriptors,
                                       Map<String, ToolCallback> toolCallbacks) {
        List<ToolDescriptor> visibleTools = orderVisibleTools(allToolDescriptors, skillMatchResult.plannedMcpTools());
        List<ToolExchange> history = new ArrayList<>();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            String prompt = buildToolAwarePrompt(userContext, conversationId, skillMatchResult, visibleTools, history, round);
            String output;
            try {
                output = executeByCli(prompt, conversationId);
            }
            catch (IllegalStateException e) {
                String fallbackAnswer = buildFallbackAnswerFromHistory(skillMatchResult, history, e);
                if (fallbackAnswer != null) {
                    log.warn("ClaudeCode CLI returned unusable output after tool success, use host fallback, conversationId={}",
                            conversationId, e);
                    return fallbackAnswer;
                }
                throw e;
            }
            CliTurn turn = parseCliTurn(output);

            if (turn.isFinal()) {
                String requiredTool = findRequiredToolBeforeFinal(skillMatchResult, history);
                if (StringUtils.hasText(requiredTool)) {
                    history.add(ToolExchange.feedback("""
                            你刚才给了最终答复，但当前请求必须先成功调用 %s，不能直接结束。
                            请下一轮严格按协议输出 tool_call，或在确实无法执行时明确说明失败原因。
                            """.formatted(requiredTool).strip()));
                    continue;
                }
                return turn.finalAnswer();
            }

            if (!turn.isToolCall()) {
                return output.strip();
            }

            ToolCallback callback = toolCallbacks.get(turn.toolName());
            if (callback == null) {
                history.add(ToolExchange.feedback("""
                        工具 %s 不可用。
                        当前可用工具：%s
                        """.formatted(turn.toolName(), joinToolNames(visibleTools)).strip()));
                continue;
            }

            JsonNode sanitizedArguments = sanitizeToolArguments(turn.toolName(), turn.arguments());
            String argumentsJson = serializeArguments(sanitizedArguments);
            try {
                String toolResult = callback.call(argumentsJson);
                history.add(ToolExchange.success(turn.toolName(), argumentsJson, toolResult, turn.reason()));
            }
            catch (RuntimeException e) {
                if (isMcpUnavailable(e)) {
                    return buildMcpUnavailableAnswer(turn.toolName(), e);
                }
                log.warn("ClaudeCode CLI tool invocation failed, conversationId={}, tool={}",
                        conversationId, turn.toolName(), e);
                history.add(ToolExchange.failure(
                        turn.toolName(),
                        argumentsJson,
                        abbreviate(e.getMessage()),
                        turn.reason()
                ));
                if (countRecentFailures(history, turn.toolName()) >= 2) {
                    return buildRepeatedToolFailureAnswer(turn.toolName(), e);
                }
            }
        }

        throw new IllegalStateException("ClaudeCode CLI 工具循环超过最大轮次，仍未返回最终答案。");
    }

    private List<ToolDescriptor> orderVisibleTools(List<ToolDescriptor> allToolDescriptors, List<String> plannedToolNames) {
        if (allToolDescriptors == null || allToolDescriptors.isEmpty()) {
            return List.of();
        }

        Map<String, ToolDescriptor> byName = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : allToolDescriptors) {
            if (descriptor != null && StringUtils.hasText(descriptor.getName())) {
                byName.putIfAbsent(descriptor.getName(), descriptor);
            }
        }

        List<ToolDescriptor> ordered = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        if (plannedToolNames != null) {
            for (String plannedToolName : plannedToolNames) {
                ToolDescriptor descriptor = byName.get(plannedToolName);
                if (descriptor != null && added.add(plannedToolName)) {
                    ordered.add(descriptor);
                }
            }
        }

        for (ToolDescriptor descriptor : byName.values()) {
            if (added.add(descriptor.getName())) {
                ordered.add(descriptor);
            }
        }

        return List.copyOf(ordered);
    }

    private String buildToolAwarePrompt(String userContext,
                                        String conversationId,
                                        AgentSkillService.SkillMatchResult skillMatchResult,
                                        List<ToolDescriptor> visibleTools,
                                        List<ToolExchange> history,
                                        int round) {
        String skillContext = StringUtils.hasText(skillMatchResult.skillContext())
                ? skillMatchResult.skillContext().strip()
                : "无";
        String plannedTools = skillMatchResult.plannedMcpTools().isEmpty()
                ? "无"
                : String.join(", ", skillMatchResult.plannedMcpTools());
        String toolCatalog = formatToolCatalog(visibleTools);
        String historySection = formatHistory(history);

        return """
                你是通过 ClaudeCode CLI 接入的企业 Agent。
                当前宿主可以代你调用外部 MCP 工具，但工具只能由宿主执行，你自己不能直接调用工具。

                输出协议，必须严格遵守：
                1. 只输出一个 JSON 对象，不要输出 markdown、代码块或额外解释。
                2. 如果需要调用工具，只输出：
                   {"type":"tool_call","tool":"工具名","arguments":{...},"reason":"简短原因"}
                3. 如果已经可以给用户最终答复，只输出：
                   {"type":"final","answer":"最终回复"}
                4. 一次只能请求一个工具。
                5. arguments 必须是合法 JSON 对象，不要把 JSON 再包成字符串。
                6. 如果用户要求生成 Word / doc / docx，除非你已经收到 generate_word_document 的成功结果，否则不要声称“已生成成功”。
                7. 如果技能已经规划了工具，优先按技能规划执行。
                8. 如果上一轮工具失败，请根据失败信息修正参数，或明确给出最终失败说明。

                会话ID：%s
                当前轮次：%d/%d

                技能说明：
                %s

                技能规划工具：
                %s

                当前可用 MCP 工具：
                %s

                用户问题与上下文：
                %s

                已有工具历史：
                %s

                现在请只输出下一步 JSON。
                """.formatted(
                conversationId,
                round,
                MAX_TOOL_ROUNDS,
                skillContext,
                plannedTools,
                toolCatalog,
                userContext.strip(),
                historySection
        );
    }

    private String formatToolCatalog(List<ToolDescriptor> visibleTools) {
        if (visibleTools == null || visibleTools.isEmpty()) {
            return "无";
        }

        StringBuilder builder = new StringBuilder();
        for (ToolDescriptor descriptor : visibleTools) {
            builder.append("- name: ").append(descriptor.getName()).append("\n")
                    .append("  description: ").append(defaultText(descriptor.getDescription(), "无")).append("\n")
                    .append("  inputSchema: ").append(abbreviateSchema(descriptor.getInputSchema())).append("\n");
        }
        return builder.toString().strip();
    }

    private String formatHistory(List<ToolExchange> history) {
        if (history == null || history.isEmpty()) {
            return "无";
        }

        StringBuilder builder = new StringBuilder();
        for (ToolExchange exchange : history) {
            switch (exchange.type()) {
                case "tool_success" -> builder.append("- 已执行工具：").append(exchange.tool()).append("\n")
                        .append("  调用原因：").append(defaultText(exchange.reason(), "未提供")).append("\n")
                        .append("  参数：").append(exchange.arguments()).append("\n")
                        .append("  结果：").append(truncateToolResult(exchange.result())).append("\n");
                case "tool_failure" -> builder.append("- 工具执行失败：").append(exchange.tool()).append("\n")
                        .append("  调用原因：").append(defaultText(exchange.reason(), "未提供")).append("\n")
                        .append("  参数：").append(exchange.arguments()).append("\n")
                        .append("  错误：").append(exchange.result()).append("\n");
                case "feedback" -> builder.append("- 宿主反馈：").append(exchange.result()).append("\n");
                default -> builder.append("- 未知历史：").append(exchange.result()).append("\n");
            }
        }
        return builder.toString().strip();
    }

    private String truncateToolResult(String result) {
        if (!StringUtils.hasText(result) || result.length() <= MAX_TOOL_RESULT_LENGTH) {
            return result;
        }
        return result.substring(0, MAX_TOOL_RESULT_LENGTH) + "\n[结果已截断，原始长度: " + result.length() + "]";
    }

    private CliTurn parseCliTurn(String output) {
        if (!StringUtils.hasText(output)) {
            return CliTurn.finalAnswer("ClaudeCode CLI 返回了空内容。");
        }

        JsonNode root = tryReadJson(output);
        if (root == null || !root.isObject()) {
            return CliTurn.finalAnswer(output.strip());
        }

        String type = root.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        String answer = root.path("answer").asText("").trim();
        String tool = root.path("tool").asText("").trim();
        JsonNode arguments = root.path("arguments");
        String reason = root.path("reason").asText("").trim();

        if ("final".equals(type) || (!StringUtils.hasText(type) && StringUtils.hasText(answer))) {
            return CliTurn.finalAnswer(StringUtils.hasText(answer) ? answer : output.strip());
        }

        if ("tool_call".equals(type)
                || "tool-call".equals(type)
                || (!StringUtils.hasText(type) && StringUtils.hasText(tool))) {
            JsonNode normalizedArguments = arguments != null && arguments.isObject()
                    ? arguments
                    : objectMapper.createObjectNode();
            return CliTurn.toolCall(tool, normalizedArguments, reason);
        }

        return CliTurn.finalAnswer(output.strip());
    }

    private JsonNode tryReadJson(String text) {
        for (String candidate : jsonCandidates(text)) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            try {
                return objectMapper.readTree(candidate);
            }
            catch (JsonProcessingException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    private List<String> jsonCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        String stripped = text == null ? "" : text.strip();
        if (StringUtils.hasText(stripped)) {
            candidates.add(stripped);
        }

        Matcher matcher = JSON_CODE_BLOCK.matcher(stripped);
        if (matcher.find()) {
            candidates.add(matcher.group(1).strip());
        }

        List<String> extractedObjects = extractTopLevelJsonObjects(stripped);
        for (int i = extractedObjects.size() - 1; i >= 0; i--) {
            candidates.add(extractedObjects.get(i));
        }

        int firstBrace = stripped.indexOf('{');
        int lastBrace = stripped.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            candidates.add(stripped.substring(firstBrace, lastBrace + 1).strip());
        }

        return candidates;
    }

    private List<String> extractTopLevelJsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return objects;
        }

        boolean inString = false;
        boolean escaping = false;
        int depth = 0;
        int objectStart = -1;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaping = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
                continue;
            }
            if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(text.substring(objectStart, i + 1).strip());
                    objectStart = -1;
                }
            }
        }

        return objects;
    }

    private String serializeArguments(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return "{}";
        }
        if (!arguments.isObject()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化工具参数失败。", e);
        }
    }

    private JsonNode sanitizeToolArguments(String toolName, JsonNode arguments) {
        if (!"generate_word_document".equals(toolName) || arguments == null || !arguments.isObject()) {
            return arguments;
        }

        JsonNode cloned = arguments.deepCopy();
        if (cloned instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode) {
            String savePath = textValue(objectNode.get("savePath"));
            if (StringUtils.hasText(savePath) && isDisallowedAbsolutePath(savePath)) {
                objectNode.remove("savePath");
            }
        }
        return cloned;
    }

    private boolean isDisallowedAbsolutePath(String savePath) {
        try {
            Path path = Path.of(savePath.trim());
            if (!path.isAbsolute()) {
                return false;
            }
            String normalized = path.normalize().toString();
            String allowedRoot = agentProperties.getClaudeCodeCli().getAllowedExportRoot();
            return !StringUtils.hasText(allowedRoot) || !normalized.startsWith(allowedRoot);
        }
        catch (Exception e) {
            return true;
        }
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private int countRecentFailures(List<ToolExchange> history, String toolName) {
        int failures = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ToolExchange exchange = history.get(i);
            if (!"tool_failure".equals(exchange.type())) {
                break;
            }
            if (toolName.equals(exchange.tool())) {
                failures++;
            } else {
                break;
            }
        }
        return failures;
    }

    private String findRequiredToolBeforeFinal(AgentSkillService.SkillMatchResult skillMatchResult,
                                               List<ToolExchange> history) {
        if (!requiresWordExportTool(skillMatchResult)) {
            return null;
        }
        return hasSuccessfulTool(history, "generate_word_document") ? null : "generate_word_document";
    }

    private boolean requiresWordExportTool(AgentSkillService.SkillMatchResult skillMatchResult) {
        return skillMatchResult != null
                && skillMatchResult.plannedMcpTools() != null
                && skillMatchResult.plannedMcpTools().contains("generate_word_document");
    }

    private boolean hasSuccessfulTool(List<ToolExchange> history, String toolName) {
        return findLastSuccessfulTool(history, toolName) != null;
    }

    private ToolExchange findLastSuccessfulTool(List<ToolExchange> history, String toolName) {
        if (history == null || history.isEmpty() || !StringUtils.hasText(toolName)) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ToolExchange exchange = history.get(i);
            if ("tool_success".equals(exchange.type()) && toolName.equals(exchange.tool())) {
                return exchange;
            }
        }
        return null;
    }

    private String buildFallbackAnswerFromHistory(AgentSkillService.SkillMatchResult skillMatchResult,
                                                  List<ToolExchange> history,
                                                  IllegalStateException error) {
        if (!isEmptyCliOutput(error) || !requiresWordExportTool(skillMatchResult)) {
            return null;
        }

        ToolExchange wordExport = findLastSuccessfulTool(history, "generate_word_document");
        if (wordExport == null) {
            return null;
        }
        return buildWordExportSuccessAnswer(wordExport.result());
    }

    private boolean isEmptyCliOutput(IllegalStateException error) {
        return error != null && StringUtils.hasText(error.getMessage())
                && error.getMessage().contains("ClaudeCode CLI 返回空内容");
    }

    private String buildWordExportSuccessAnswer(String toolResult) {
        JsonNode result = tryReadJson(toolResult);
        if (result != null && result.isObject()) {
            boolean success = result.path("success").asBoolean(true);
            String path = result.path("path").asText("").trim();
            String fileName = result.path("fileName").asText("").trim();
            long size = result.path("size").asLong(-1L);
            if (success && (StringUtils.hasText(path) || StringUtils.hasText(fileName))) {
                StringBuilder answer = new StringBuilder("Word 文档已生成成功。");
                if (StringUtils.hasText(fileName)) {
                    answer.append("\n文件名：").append(fileName);
                }
                if (StringUtils.hasText(path)) {
                    answer.append("\n保存路径：").append(path);
                }
                if (size >= 0) {
                    answer.append("\n文件大小：").append(size).append(" bytes");
                }
                return answer.toString();
            }
        }
        return """
                Word 文档已生成成功。
                工具返回：%s
                """.formatted(defaultText(abbreviate(toolResult, 600), "generate_word_document 已成功执行"));
    }

    private boolean isMcpUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ResourceAccessException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildMcpUnavailableAnswer(String toolName, RuntimeException error) {
        return """
                当前无法完成需要 MCP 工具的操作，因为 aip-mcp-server 暂时不可用。

                失败工具：%s
                原因：%s

                请先确认 10667 端口对应的 MCP 服务已启动，再重试。推荐执行：
                scripts/dev.sh restart all --force
                """.formatted(toolName, defaultText(abbreviate(error.getMessage()), "连接 MCP 服务失败"));
    }

    private String buildRepeatedToolFailureAnswer(String toolName, RuntimeException error) {
        return """
                当前请求已经命中所需工具，但工具连续执行失败，因此本次不再继续重试。

                失败工具：%s
                原因：%s

                如果这是 Word 导出请求，请优先检查文件名和保存路径是否符合 MCP 服务允许范围，然后重试。
                """.formatted(toolName, defaultText(abbreviate(error.getMessage()), "工具执行失败"));
    }

    private String resolveConversationId(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
    }

    private String buildUserContext(String question) {
        return """
                用户问题：
                %s
                """.formatted(question.strip());
    }

    private String buildUserContextWithFiles(String question,
                                             MultipartFile[] files,
                                             List<String> fileNames) {
        StringBuilder prompt = new StringBuilder(buildUserContext(question));
        prompt.append("\n以下是用户上传文件的真实内容，请优先基于这些内容回答：\n");

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String fileName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename()
                    : "unnamed-file";
            fileNames.add(fileName);

            prompt.append("\n=== 文件开始 ===\n");
            prompt.append("文件名：").append(fileName).append("\n");
            prompt.append("Content-Type：").append(file.getContentType()).append("\n");
            prompt.append("大小：").append(file.getSize()).append(" bytes\n");
            prompt.append("内容：\n").append(extractFileContent(file)).append("\n");
            prompt.append("=== 文件结束 ===\n");
        }

        if (fileNames.isEmpty()) {
            throw new IllegalArgumentException("没有可读取的上传文件");
        }
        return prompt.toString();
    }

    private String extractFileContent(MultipartFile file) {
        if (!isTextFile(file)) {
            return "[该文件不是可直接读取的文本类型，当前仅提供文件名、类型和大小供参考。]";
        }

        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.length() <= MAX_FILE_CHAR_COUNT) {
                return content;
            }
            return content.substring(0, MAX_FILE_CHAR_COUNT)
                    + "\n[文件内容过长，已截断，原始字符数: " + content.length() + "]";
        }
        catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败: " + file.getOriginalFilename(), e);
        }
    }

    private boolean isTextFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String lowerContentType = contentType.toLowerCase(Locale.ROOT);
            if (lowerContentType.startsWith("text/")
                    || lowerContentType.contains("json")
                    || lowerContentType.contains("xml")
                    || lowerContentType.contains("yaml")
                    || lowerContentType.contains("javascript")) {
                return true;
            }
        }

        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            return false;
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return TEXT_FILE_EXTENSIONS.contains(extension);
    }

    private String executeByCli(String prompt, String conversationId) {
        AgentProperties.ClaudeCodeCli cli = agentProperties.getClaudeCodeCli();
        List<String> command = buildCommand(cli, prompt, conversationId);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        if (StringUtils.hasText(cli.getWorkingDirectory())) {
            processBuilder.directory(new File(cli.getWorkingDirectory().strip()));
        }
        if (cli.getEnvironment() != null && !cli.getEnvironment().isEmpty()) {
            processBuilder.environment().putAll(cli.getEnvironment());
        }

        long timeoutSeconds = Math.max(1, cli.getTimeoutSeconds());
        try {
            Process process = processBuilder.start();
            process.getOutputStream().close();
            CliOutputCollector outputCollector = startOutputCollector(process.getInputStream());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                String partialOutput = outputCollector.awaitOutput(5);
                throw new IllegalStateException("ClaudeCode CLI 执行超时，timeoutSeconds=" + timeoutSeconds
                        + "，最近输出=" + abbreviate(partialOutput));
            }

            String output = outputCollector.awaitOutput(5).strip();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException("ClaudeCode CLI 执行失败，exitCode=" + exitCode
                        + "，输出=" + abbreviate(output));
            }
            if (!StringUtils.hasText(output)) {
                throw new IllegalStateException("ClaudeCode CLI 返回空内容，请检查 command-template 配置。");
            }
            return output;
        }
        catch (IOException e) {
            throw new IllegalStateException("无法启动 ClaudeCode CLI，请确认命令存在且可执行。", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 ClaudeCode CLI 结果时被中断。", e);
        }
        catch (CliOutputReadException e) {
            log.warn("读取 ClaudeCode CLI 输出失败，conversationId={}", conversationId, e);
            throw new IllegalStateException("读取 ClaudeCode CLI 输出失败。", e);
        }
    }

    private List<String> buildCommand(AgentProperties.ClaudeCodeCli cli, String prompt, String conversationId) {
        List<String> template = cli.getCommandTemplate();
        if (template == null || template.isEmpty()) {
            throw new IllegalStateException("aip.agent.claude-code-cli.command-template 不能为空");
        }

        List<String> command = new ArrayList<>();
        boolean injectedPrompt = false;
        for (String part : template) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String resolved = part
                    .replace("{conversationId}", conversationId)
                    .replace("{agentType}", agentType());
            if (resolved.contains("{prompt}")) {
                injectedPrompt = true;
                resolved = resolved.replace("{prompt}", prompt);
            }
            command.add(resolved);
        }

        if (cli.isAppendPromptWhenMissing() && !injectedPrompt) {
            command.add(prompt);
        }
        if (command.isEmpty()) {
            throw new IllegalStateException("command-template 解析后为空，请检查配置。");
        }
        return command;
    }

    private CliOutputCollector startOutputCollector(InputStream inputStream) {
        CliOutputCollector collector = new CliOutputCollector(inputStream);
        collector.start();
        return collector;
    }

    private static final class CliOutputCollector {

        private final InputStream inputStream;

        private final StringBuilder output = new StringBuilder();

        private volatile CliOutputReadException failure;

        private Thread thread;

        private CliOutputCollector(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        private void start() {
            thread = new Thread(this::collect, "claude-cli-output-reader");
            thread.setDaemon(true);
            thread.start();
        }

        private void collect() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        if (!output.isEmpty()) {
                            output.append('\n');
                        }
                        output.append(line);
                    }
                }
            }
            catch (IOException e) {
                failure = new CliOutputReadException("读取 ClaudeCode CLI 输出流失败。", e);
            }
        }

        private String awaitOutput(long timeoutSeconds) throws InterruptedException {
            if (thread != null) {
                thread.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
                if (thread.isAlive()) {
                    throw new CliOutputReadException("读取 ClaudeCode CLI 输出失败：等待输出线程超时。");
                }
            }
            if (failure != null) {
                throw failure;
            }
            synchronized (output) {
                return output.toString();
            }
        }
    }

    private static final class CliOutputReadException extends RuntimeException {

        private CliOutputReadException(String message) {
            super(message);
        }

        private CliOutputReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String defaultText(String text, String fallback) {
        return StringUtils.hasText(text) ? text.strip() : fallback;
    }

    private String abbreviateSchema(String schema) {
        return StringUtils.hasText(schema) ? abbreviate(schema, 1200) : "{}";
    }

    private String joinToolNames(List<ToolDescriptor> descriptors) {
        return descriptors.stream()
                .map(ToolDescriptor::getName)
                .filter(StringUtils::hasText)
                .toList()
                .toString();
    }

    private String abbreviate(String text) {
        return abbreviate(text, 300);
    }

    private String abbreviate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private record CliTurn(String type, String finalAnswer, String toolName, JsonNode arguments, String reason) {

        static CliTurn finalAnswer(String answer) {
            return new CliTurn("final", answer, null, null, null);
        }

        static CliTurn toolCall(String toolName, JsonNode arguments, String reason) {
            return new CliTurn("tool_call", null, toolName, arguments, reason);
        }

        boolean isFinal() {
            return "final".equals(type);
        }

        boolean isToolCall() {
            return "tool_call".equals(type) && StringUtils.hasText(toolName);
        }
    }

    private record ToolExchange(String type, String tool, String arguments, String result, String reason) {

        static ToolExchange success(String tool, String arguments, String result, String reason) {
            return new ToolExchange("tool_success", tool, arguments, result, reason);
        }

        static ToolExchange failure(String tool, String arguments, String result, String reason) {
            return new ToolExchange("tool_failure", tool, arguments, result, reason);
        }

        static ToolExchange feedback(String result) {
            return new ToolExchange("feedback", null, null, result, null);
        }
    }
}
