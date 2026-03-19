package com.aip.mcp.tool;

import com.aip.mcp.support.FilePathSecurityManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WordDocumentTool implements McpToolHandler {

    private final ObjectMapper objectMapper;

    private final FilePathSecurityManager filePathSecurityManager;

    public WordDocumentTool(ObjectMapper objectMapper, FilePathSecurityManager filePathSecurityManager) {
        this.objectMapper = objectMapper;
        this.filePathSecurityManager = filePathSecurityManager;
    }

    @Tool(name = "generate_word_document", description = "生成 Word 文档并保存为 docx 文件")
    public String generateWordDocument(
            @ToolParam(required = false, description = "文档标题，可为空") String title,
            @ToolParam(description = "文档正文，支持按换行拆分为多个段落") String content,
            @ToolParam(required = false, description = "文件名，例如 周报.docx，默认自动生成") String fileName,
            @ToolParam(required = false, description = "保存路径。可传 storage/files 下的相对路径，或 /Users/qijian/Desktop/爱化身 下的绝对路径；既支持目录也支持完整文件路径") String savePath) {
        try {
            Path targetPath = resolveTargetPath(fileName, savePath);
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (XWPFDocument document = new XWPFDocument();
                 OutputStream outputStream = Files.newOutputStream(targetPath)) {
                if (hasText(title)) {
                    XWPFParagraph titleParagraph = document.createParagraph();
                    titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun titleRun = titleParagraph.createRun();
                    titleRun.setBold(true);
                    titleRun.setFontSize(16);
                    titleRun.setText(title.trim());
                }

                String[] paragraphs = normalizeContent(content).split("\\R", -1);
                for (String paragraphText : paragraphs) {
                    XWPFParagraph paragraph = document.createParagraph();
                    XWPFRun run = paragraph.createRun();
                    run.setFontSize(12);
                    run.setText(paragraphText);
                }

                document.write(outputStream);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("fileName", targetPath.getFileName().toString());
            result.put("path", targetPath.toAbsolutePath().normalize().toString());
            result.put("size", Files.size(targetPath));
            return toJson(result);
        }
        catch (IOException e) {
            throw new IllegalStateException("生成 Word 文档失败: " + e.getMessage(), e);
        }
    }

    private Path resolveTargetPath(String fileName, String savePath) {
        String resolvedFileName = normalizeFileName(hasText(fileName)
                ? fileName.trim()
                : filePathSecurityManager.generateSafeRelativePath("word-document.docx"));

        if (hasText(savePath)) {
            Path requestedPath = Path.of(savePath.trim());
            if (!requestedPath.isAbsolute()) {
                requestedPath = filePathSecurityManager.getGeneralFileRoot().resolve(requestedPath);
            }

            Path candidatePath = resolveSaveTarget(requestedPath, resolvedFileName);
            String allowedType = resolveAllowedType(candidatePath);
            return ensureDocxExtension(filePathSecurityManager.validateAndNormalizePath(candidatePath.toString(), allowedType));
        }

        return ensureDocxExtension(filePathSecurityManager.getGeneralFileRoot().resolve(resolvedFileName).normalize());
    }

    private Path resolveSaveTarget(Path savePath, String fileName) {
        String rawPath = savePath.toString();
        boolean explicitDirectory = rawPath.endsWith("/") || rawPath.endsWith("\\");
        boolean existingDirectory = Files.exists(savePath) && Files.isDirectory(savePath);
        boolean noExtension = !savePath.getFileName().toString().contains(".");

        if (explicitDirectory || existingDirectory || noExtension) {
            return savePath.resolve(fileName).normalize();
        }
        return savePath.normalize();
    }

    private String resolveAllowedType(Path path) {
        if (filePathSecurityManager.isInGeneralFileRoot(path)) {
            return "file";
        }
        if (filePathSecurityManager.isInExternalExportRoot(path)) {
            return "export";
        }
        throw new IllegalArgumentException("保存路径不允许，当前仅支持 storage/files 或 /Users/qijian/Desktop/爱化身 下的路径");
    }

    private String normalizeFileName(String fileName) {
        if (!hasText(fileName)) {
            return "word-document.docx";
        }
        return Path.of(fileName).getFileName().toString();
    }

    private Path ensureDocxExtension(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.toLowerCase().endsWith(".docx")) {
            return path;
        }

        Path parent = path.getParent();
        String resolvedFileName = fileName + ".docx";
        return parent == null ? Path.of(resolvedFileName) : parent.resolve(resolvedFileName);
    }

    private String normalizeContent(String content) {
        if (!hasText(content)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        return content.strip();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Word 结果序列化失败", e);
        }
    }
}
