package com.aip.mcp.tool;

import com.aip.mcp.config.McpProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileSystemTool implements McpToolHandler {

    private final Path workspaceRoot;

    private final ObjectMapper objectMapper;

    public FileSystemTool(McpProperties properties, ObjectMapper objectMapper) {
        this.workspaceRoot = Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Tool(name = "create_file_or_directory", description = "在工作区内创建文件或目录，自动创建父目录")
    public String createFileOrDirectory(
            @ToolParam(description = "工作区内的相对路径，或工作区内绝对路径") String path,
            @ToolParam(required = false, description = "文件内容，留空时创建目录") String content) {
        try {
            Path targetPath = resolvePath(path);
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (hasText(content)) {
                Files.writeString(targetPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return "成功创建文件: " + targetPath;
            }

            Files.createDirectories(targetPath);
            return "成功创建目录: " + targetPath;
        }
        catch (IOException e) {
            throw new IllegalStateException("创建失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "read_file", description = "读取工作区内文件的完整内容")
    public String readFile(@ToolParam(description = "文件路径") String path) {
        try {
            return Files.readString(resolvePath(path));
        }
        catch (IOException e) {
            throw new IllegalStateException("读取失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "modify_file", description = "修改或追加工作区内文件内容")
    public String modifyFile(
            @ToolParam(description = "文件路径") String path,
            @ToolParam(description = "新的文件内容") String content,
            @ToolParam(required = false, description = "是否追加，默认 false") Boolean append) {
        try {
            Path filePath = resolvePath(path);
            if (!Files.exists(filePath)) {
                throw new IllegalArgumentException("文件不存在: " + filePath);
            }

            if (Boolean.TRUE.equals(append)) {
                Files.writeString(filePath, content == null ? "" : content, StandardOpenOption.APPEND);
            }
            else {
                Files.writeString(filePath, content == null ? "" : content, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return "成功修改文件: " + filePath;
        }
        catch (IOException e) {
            throw new IllegalStateException("修改失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "delete_file_or_directory", description = "删除工作区内的文件或目录")
    public String deleteFileOrDirectory(@ToolParam(description = "目标路径") String path) {
        Path targetPath = resolvePath(path);
        if (!Files.exists(targetPath)) {
            return "路径不存在: " + targetPath;
        }

        try {
            Files.walkFileTree(targetPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return "成功删除: " + targetPath;
        }
        catch (IOException e) {
            throw new IllegalStateException("删除失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "scan_directory", description = "扫描目录结构并返回文件和子目录列表")
    public String scanDirectory(
            @ToolParam(description = "目录路径") String path,
            @ToolParam(required = false, description = "最大深度，默认 3") Integer maxDepth,
            @ToolParam(required = false, description = "是否包含隐藏文件，默认 false") Boolean includeHidden) {
        try {
            Path rootPath = resolvePath(path);
            if (!Files.isDirectory(rootPath)) {
                throw new IllegalArgumentException("不是有效目录: " + rootPath);
            }

            int depth = maxDepth == null ? 3 : maxDepth;
            boolean showHidden = Boolean.TRUE.equals(includeHidden);

            List<Map<String, Object>> children = new ArrayList<>();
            Files.walk(rootPath, depth)
                    .skip(1)
                    .filter(candidate -> showHidden || !candidate.getFileName().toString().startsWith("."))
                    .forEach(candidate -> children.add(describePath(candidate)));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", rootPath.toString());
            result.put("workspaceRoot", workspaceRoot.toString());
            result.put("children", children);
            result.put("total", children.size());
            return toJson(result);
        }
        catch (IOException e) {
            throw new IllegalStateException("扫描失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "copy_file_or_directory", description = "复制工作区内文件或目录")
    public String copyFileOrDirectory(
            @ToolParam(description = "源路径") String source,
            @ToolParam(description = "目标路径") String target) {
        try {
            Path sourcePath = resolvePath(source);
            Path targetPath = resolvePath(target);

            if (!Files.exists(sourcePath)) {
                throw new IllegalArgumentException("源路径不存在: " + sourcePath);
            }

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (Files.isDirectory(sourcePath)) {
                copyDirectory(sourcePath, targetPath);
            }
            else {
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return "成功复制: " + sourcePath + " -> " + targetPath;
        }
        catch (IOException e) {
            throw new IllegalStateException("复制失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "move_file_or_directory", description = "移动工作区内文件或目录")
    public String moveFileOrDirectory(
            @ToolParam(description = "源路径") String source,
            @ToolParam(description = "目标路径") String target) {
        try {
            Path sourcePath = resolvePath(source);
            Path targetPath = resolvePath(target);

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return "成功移动: " + sourcePath + " -> " + targetPath;
        }
        catch (IOException e) {
            throw new IllegalStateException("移动失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "search_files", description = "按 glob 模式搜索工作区内文件")
    public String searchFiles(
            @ToolParam(description = "搜索根目录") String directory,
            @ToolParam(description = "文件名模式，例如 *.java 或 pom.xml") String pattern,
            @ToolParam(required = false, description = "最大搜索深度，默认 5") Integer maxDepth) {
        try {
            Path rootPath = resolvePath(directory);
            if (!Files.isDirectory(rootPath)) {
                throw new IllegalArgumentException("不是有效目录: " + rootPath);
            }

            int depth = maxDepth == null ? 5 : maxDepth;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<String> files = Files.walk(rootPath, depth)
                    .filter(Files::isRegularFile)
                    .filter(file -> matcher.matches(file.getFileName()))
                    .map(Path::toString)
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directory", rootPath.toString());
            result.put("pattern", pattern);
            result.put("count", files.size());
            result.put("files", files);
            return toJson(result);
        }
        catch (IOException e) {
            throw new IllegalStateException("搜索失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "get_file_info", description = "获取文件或目录的详细信息")
    public String getFileInfo(@ToolParam(description = "目标路径") String path) {
        try {
            Path targetPath = resolvePath(path);
            if (!Files.exists(targetPath)) {
                throw new IllegalArgumentException("路径不存在: " + targetPath);
            }

            BasicFileAttributes attributes = Files.readAttributes(targetPath, BasicFileAttributes.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", targetPath.toString());
            result.put("name", targetPath.getFileName().toString());
            result.put("directory", attributes.isDirectory());
            result.put("regularFile", attributes.isRegularFile());
            result.put("size", attributes.size());
            result.put("creationTime", attributes.creationTime().toString());
            result.put("lastModifiedTime", attributes.lastModifiedTime().toString());
            result.put("lastAccessTime", attributes.lastAccessTime().toString());
            result.put("hidden", Files.isHidden(targetPath));
            result.put("readable", Files.isReadable(targetPath));
            result.put("writable", Files.isWritable(targetPath));
            result.put("executable", Files.isExecutable(targetPath));
            return toJson(result);
        }
        catch (IOException e) {
            throw new IllegalStateException("获取文件信息失败: " + e.getMessage(), e);
        }
    }

    private void copyDirectory(Path sourcePath, Path targetPath) throws IOException {
        Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relativePath = sourcePath.relativize(dir);
                Files.createDirectories(targetPath.resolve(relativePath));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relativePath = sourcePath.relativize(file);
                Files.copy(file, targetPath.resolve(relativePath), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Map<String, Object> describePath(Path candidate) {
        Map<String, Object> fileInfo = new LinkedHashMap<>();
        fileInfo.put("name", candidate.getFileName().toString());
        fileInfo.put("path", candidate.toString());
        fileInfo.put("directory", Files.isDirectory(candidate));

        if (!Files.isDirectory(candidate)) {
            try {
                fileInfo.put("size", Files.size(candidate));
            }
            catch (IOException e) {
                fileInfo.put("size", -1);
            }
        }
        return fileInfo;
    }

    private Path resolvePath(String path) {
        if (!hasText(path)) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path candidate = Paths.get(path);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();

        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("仅允许访问工作区内路径: " + workspaceRoot);
        }
        return candidate;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("结果序列化失败", e);
        }
    }
}
