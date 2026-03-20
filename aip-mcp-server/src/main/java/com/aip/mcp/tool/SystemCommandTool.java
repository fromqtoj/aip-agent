package com.aip.mcp.tool;

import com.aip.mcp.config.McpProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SystemCommandTool implements McpToolHandler {

    private static final Pattern EXECUTABLE_PATTERN = Pattern.compile("^\\s*([\\w./-]+)");

    private static final Pattern SHELL_META_CHARS = Pattern.compile("[;|&$`()><{}\\[\\]!#]");

    private static final Set<String> INTERNAL_COMMANDS = Set.of("which", "where", "ps", "tasklist");

    private final Path workspaceRoot;

    private final Set<String> allowList;

    private final int defaultTimeoutSeconds;

    public SystemCommandTool(McpProperties properties) {
        this.workspaceRoot = Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
        this.allowList = new HashSet<>(properties.getCommand().getAllowList());
        this.defaultTimeoutSeconds = properties.getCommand().getTimeoutSeconds();
    }

    @Tool(name = "execute_command", description = "在白名单约束下执行系统命令")
    public String executeCommand(
            @ToolParam(description = "要执行的命令，例如 git status") String command,
            @ToolParam(required = false, description = "工作目录，必须位于工作区内") String workingDirectory,
            @ToolParam(required = false, description = "超时时间，单位秒") Integer timeoutSeconds) {
        validateCommand(command);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (isWindows()) {
                processBuilder.command("cmd.exe", "/c", command);
            }
            else {
                processBuilder.command("sh", "-c", command);
            }

            processBuilder.directory(resolveWorkingDirectory(workingDirectory).toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            int timeout = timeoutSeconds == null ? defaultTimeoutSeconds : timeoutSeconds;
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时: " + command;
            }

            String output = readOutput(process);
            return "退出码: " + process.exitValue() + "\n输出:\n" + output;
        }
        catch (IOException e) {
            throw new IllegalStateException("命令执行失败: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行被中断", e);
        }
    }

    @Tool(name = "check_command_exists", description = "检查系统中是否存在某个命令")
    public String checkCommandExists(@ToolParam(description = "命令名称，例如 git") String command) {
        String checkCommand = isWindows() ? "where " + command : "which " + command;
        return executeInternalCommand(checkCommand, 10);
    }

    @Tool(name = "list_processes", description = "查看当前系统进程列表")
    public String listProcesses() {
        String command = isWindows() ? "tasklist" : "ps aux";
        return executeInternalCommand(command, 10);
    }

    @Tool(name = "execute_command_async", description = "异步执行系统命令，立即返回进程 ID")
    public String executeCommandAsync(
            @ToolParam(description = "要执行的命令") String command,
            @ToolParam(required = false, description = "工作目录，必须位于工作区内") String workingDirectory) {
        validateCommand(command);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (isWindows()) {
                processBuilder.command("cmd.exe", "/c", command);
            }
            else {
                processBuilder.command("sh", "-c", command);
            }
            processBuilder.directory(resolveWorkingDirectory(workingDirectory).toFile());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            return "命令已启动\nPID: " + process.pid() + "\n命令: " + command;
        }
        catch (IOException e) {
            throw new IllegalStateException("异步命令启动失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "kill_process", description = "终止指定 PID 的进程")
    public String killProcess(@ToolParam(description = "进程 ID") Long pid) {
        return ProcessHandle.of(pid)
                .map(processHandle -> processHandle.destroy() || processHandle.destroyForcibly()
                        ? "成功终止进程: " + pid
                        : "终止进程失败: " + pid)
                .orElse("进程不存在: " + pid);
    }

    private String executeInternalCommand(String command, int timeoutSeconds) {
        validateCommand(command, true);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (isWindows()) {
                processBuilder.command("cmd.exe", "/c", command);
            } else {
                processBuilder.command("sh", "-c", command);
            }
            processBuilder.directory(workspaceRoot.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时: " + command;
            }

            String output = readOutput(process);
            return "退出码: " + process.exitValue() + "\n输出:\n" + output;
        } catch (IOException e) {
            throw new IllegalStateException("命令执行失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行被中断", e);
        }
    }

    private void validateCommand(String command) {
        validateCommand(command, false);
    }

    private void validateCommand(String command, boolean allowInternal) {
        Matcher matcher = EXECUTABLE_PATTERN.matcher(command == null ? "" : command);
        if (!matcher.find()) {
            throw new IllegalArgumentException("命令不能为空");
        }

        String executable = Paths.get(matcher.group(1)).getFileName().toString();
        boolean allowed = allowList.contains(executable)
                || (allowInternal && INTERNAL_COMMANDS.contains(executable));
        if (!allowed) {
            throw new IllegalArgumentException("命令未在白名单中: " + executable);
        }

        if (SHELL_META_CHARS.matcher(command).find()) {
            throw new IllegalArgumentException("命令包含不允许的特殊字符，禁止使用 ;|&$`()><{} 等 shell 元字符");
        }
    }

    private Path resolveWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return workspaceRoot;
        }

        Path candidate = Paths.get(workingDirectory);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();

        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("工作目录必须位于工作区内: " + workspaceRoot);
        }
        return candidate;
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
