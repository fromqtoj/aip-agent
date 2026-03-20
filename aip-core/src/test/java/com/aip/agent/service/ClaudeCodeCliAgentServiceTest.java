package com.aip.agent.service;

import com.aip.agent.config.AgentProperties;
import com.aip.mcp.dto.ToolDescriptor;
import com.aip.skill.AgentSkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCodeCliAgentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldForceWordToolBeforeAcceptingFinalAnswer() throws Exception {
        Path script = createScript("""
                #!/bin/sh
                STATE_FILE="%s"
                count=0
                if [ -f "$STATE_FILE" ]; then
                  count=$(cat "$STATE_FILE")
                fi
                count=$((count + 1))
                echo "$count" > "$STATE_FILE"
                case "$count" in
                  1)
                    printf '我先完成文章。\\n\\n{"type":"final","answer":"只有正文，还没导出"}'
                    ;;
                  2)
                    printf '{"type":"tool_call","tool":"generate_word_document","arguments":{"title":"东北烧烤：人间烟火最抚人心","content":"正文内容","fileName":"dongbei-bbq-test.docx"},"reason":"用户要求导出 Word"}'
                    ;;
                  *)
                    printf '补一句说明\\n{"type":"final","answer":"Word 已生成，路径已返回。"}'
                    ;;
                esac
                """.formatted(tempDir.resolve("state-force.txt")));

        ClaudeCodeCliAgentService service = createService(script);
        AtomicInteger toolCallCount = new AtomicInteger();
        Map<String, ToolCallback> callbacks = Map.of(
                "generate_word_document",
                toolCallback("generate_word_document", input -> {
                    toolCallCount.incrementAndGet();
                    return """
                            {"success":true,"fileName":"dongbei-bbq-test.docx","path":"/Users/qijian/Desktop/爱化身/dongbei-bbq-test.docx","size":1234}
                            """.strip();
                })
        );

        String answer = invokeExecuteToolAwareCli(service, wordSkillMatchResult(), callbacks);

        assertEquals("Word 已生成，路径已返回。", answer);
        assertEquals(1, toolCallCount.get());
    }

    @Test
    void shouldFallbackToToolResultWhenSecondCliRoundIsEmpty(@TempDir Path testDir) throws Exception {
        Path script = createScript("""
                #!/bin/sh
                STATE_FILE="%s"
                count=0
                if [ -f "$STATE_FILE" ]; then
                  count=$(cat "$STATE_FILE")
                fi
                count=$((count + 1))
                echo "$count" > "$STATE_FILE"
                case "$count" in
                  1)
                    printf '{"type":"tool_call","tool":"generate_word_document","arguments":{"title":"东北烧烤：人间烟火最抚人心","content":"正文内容","fileName":"dongbei-bbq-fallback.docx"},"reason":"导出 Word"}'
                    ;;
                  *)
                    exit 0
                    ;;
                esac
                """.formatted(testDir.resolve("state-fallback.txt")));

        ClaudeCodeCliAgentService service = createService(script);
        Map<String, ToolCallback> callbacks = Map.of(
                "generate_word_document",
                toolCallback("generate_word_document", input -> """
                        {"success":true,"fileName":"dongbei-bbq-fallback.docx","path":"/Users/qijian/Desktop/爱化身/dongbei-bbq-fallback.docx","size":3589}
                        """.strip())
        );

        String answer = invokeExecuteToolAwareCli(service, wordSkillMatchResult(), callbacks);

        assertTrue(answer.contains("Word 文档已生成成功。"));
        assertTrue(answer.contains("dongbei-bbq-fallback.docx"));
        assertTrue(answer.contains("/Users/qijian/Desktop/爱化身/dongbei-bbq-fallback.docx"));
    }

    @Test
    void shouldIncludePartialCliOutputWhenTimedOut() throws Exception {
        Path script = createScript("""
                #!/bin/sh
                printf 'API error: getaddrinfo ENOTFOUND api.anthropic.com'
                sleep 2
                """);

        ClaudeCodeCliAgentService service = createService(script, 1);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                () -> invokeExecuteByCliReflectively(service, "测试超时输出", "timeout-conversation-id")
        );
        Throwable cause = error.getCause();
        assertTrue(cause instanceof IllegalStateException);

        assertTrue(cause.getMessage().contains("ClaudeCode CLI 执行超时"));
        assertTrue(cause.getMessage().contains("ENOTFOUND api.anthropic.com"));
    }

    private ClaudeCodeCliAgentService createService(Path script) {
        return createService(script, 10);
    }

    private ClaudeCodeCliAgentService createService(Path script, long timeoutSeconds) {
        AgentProperties properties = new AgentProperties();
        properties.setActive("claude-code-cli");
        properties.getClaudeCodeCli().setEnabled(true);
        properties.getClaudeCodeCli().setWorkingDirectory(tempDir.toString());
        properties.getClaudeCodeCli().setAppendPromptWhenMissing(false);
        properties.getClaudeCodeCli().setTimeoutSeconds(timeoutSeconds);
        properties.getClaudeCodeCli().setCommandTemplate(List.of(script.toString(), "{prompt}"));
        properties.getClaudeCodeCli().setEnvironment(Map.of());
        return new ClaudeCodeCliAgentService(properties, null, null, null, new ObjectMapper());
    }

    private Path createScript(String content) throws IOException {
        Path script = tempDir.resolve("mock-claude-" + System.nanoTime() + ".sh");
        Files.writeString(script, content, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return script;
    }

    private AgentSkillService.SkillMatchResult wordSkillMatchResult() {
        AgentSkillService.MatchedSkill matchedSkill = new AgentSkillService.MatchedSkill(
                "word-export-planning.skill.md",
                "Word Export Planning",
                100,
                4,
                false,
                List.of("word", "docx"),
                "test",
                List.of("generate_word_document")
        );
        return new AgentSkillService.SkillMatchResult(
                "Word Export Planning",
                List.of(matchedSkill),
                List.of("generate_word_document")
        );
    }

    @SuppressWarnings("unchecked")
    private String invokeExecuteToolAwareCli(ClaudeCodeCliAgentService service,
                                             AgentSkillService.SkillMatchResult skillMatchResult,
                                             Map<String, ToolCallback> callbacks) throws Exception {
        Method method = ClaudeCodeCliAgentService.class.getDeclaredMethod(
                "executeToolAwareCli",
                String.class,
                String.class,
                AgentSkillService.SkillMatchResult.class,
                List.class,
                Map.class
        );
        method.setAccessible(true);

        ToolDescriptor descriptor = new ToolDescriptor(
                "generate_word_document",
                "生成 Word 文档并保存为 docx 文件",
                "{\"type\":\"object\"}"
        );
        return (String) method.invoke(
                service,
                "用户问题：请导出 Word",
                "test-conversation-id",
                skillMatchResult,
                List.of(descriptor),
                new LinkedHashMap<>(callbacks)
        );
    }

    private String invokeExecuteByCliReflectively(ClaudeCodeCliAgentService service,
                                                  String prompt,
                                                  String conversationId) throws Exception {
        Method method = ClaudeCodeCliAgentService.class.getDeclaredMethod(
                "executeByCli",
                String.class,
                String.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, prompt, conversationId);
    }

    private ToolCallback toolCallback(String name, ToolExecutor executor) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test")
                .inputSchema("{\"type\":\"object\"}")
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return executor.execute(toolInput);
            }
        };
    }

    @FunctionalInterface
    private interface ToolExecutor {
        String execute(String toolInput);
    }
}
