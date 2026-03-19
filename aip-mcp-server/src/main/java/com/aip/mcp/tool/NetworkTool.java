package com.aip.mcp.tool;

import com.aip.mcp.support.FilePathSecurityManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NetworkTool implements McpToolHandler {

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final FilePathSecurityManager filePathSecurityManager;

    public NetworkTool(ObjectMapper objectMapper, FilePathSecurityManager filePathSecurityManager) {
        this.objectMapper = objectMapper;
        this.filePathSecurityManager = filePathSecurityManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Tool(name = "http_get", description = "发送 HTTP GET 请求")
    public String httpGet(
            @ToolParam(description = "请求 URL") String url,
            @ToolParam(required = false, description = "请求头 JSON，例如 {\"Authorization\":\"Bearer xxx\"}") String headers) {
        return execute(url, "GET", null, null, headers);
    }

    @Tool(name = "http_post", description = "发送 HTTP POST 请求")
    public String httpPost(
            @ToolParam(description = "请求 URL") String url,
            @ToolParam(required = false, description = "请求体内容") String body,
            @ToolParam(required = false, description = "Content-Type，默认 application/json") String contentType,
            @ToolParam(required = false, description = "请求头 JSON") String headers) {
        return execute(url, "POST", body, contentType, headers);
    }

    @Tool(name = "http_put", description = "发送 HTTP PUT 请求")
    public String httpPut(
            @ToolParam(description = "请求 URL") String url,
            @ToolParam(required = false, description = "请求体内容") String body,
            @ToolParam(required = false, description = "Content-Type，默认 application/json") String contentType,
            @ToolParam(required = false, description = "请求头 JSON") String headers) {
        return execute(url, "PUT", body, contentType, headers);
    }

    @Tool(name = "http_delete", description = "发送 HTTP DELETE 请求")
    public String httpDelete(
            @ToolParam(description = "请求 URL") String url,
            @ToolParam(required = false, description = "请求头 JSON") String headers) {
        return execute(url, "DELETE", null, null, headers);
    }

    @Tool(name = "download_file", description = "从 URL 下载文件并保存到本地")
    public String downloadFile(
            @ToolParam(description = "文件 URL") String url,
            @ToolParam(required = false, description = "保存路径，留空时自动保存到通用文件目录") String savePath) {
        try {
            Path targetPath;
            if (savePath == null || savePath.isBlank()) {
                String fileName = extractFilename(url);
                String safeName = filePathSecurityManager.generateSafeRelativePath(fileName);
                targetPath = filePathSecurityManager.validateAndNormalizePath(
                        filePathSecurityManager.getGeneralFileRoot().resolve(safeName).toString(), "general");
            }
            else {
                Path requestedPath = Path.of(savePath);
                if (!requestedPath.isAbsolute()) {
                    requestedPath = filePathSecurityManager.getGeneralFileRoot().resolve(requestedPath);
                }
                targetPath = filePathSecurityManager.validateAndNormalizePath(requestedPath.toString(), "general");
            }

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.statusCode());
            result.put("path", targetPath.toString());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e) {
            throw new IllegalStateException("文件下载失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "upload_file", description = "上传文件到服务器")
    public String uploadFile(
            @ToolParam(description = "上传 URL") String url,
            @ToolParam(description = "文件路径") String filePath,
            @ToolParam(required = false, description = "表单字段名，默认 file") String fieldName) {
        try {
            Path sourcePath = Path.of(filePath);
            if (!sourcePath.isAbsolute()) {
                sourcePath = filePathSecurityManager.getProjectWorkspaceRoot().resolve(sourcePath).normalize();
            }
            if (!Files.exists(sourcePath)) {
                throw new IllegalArgumentException("文件不存在: " + sourcePath);
            }

            String boundary = "----AipAgentBoundary" + System.currentTimeMillis();
            String formField = fieldName == null || fieldName.isBlank() ? "file" : fieldName;
            byte[] fileBytes = Files.readAllBytes(sourcePath);
            String fileName = sourcePath.getFileName().toString();

            String prefix = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + formField + "\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            String suffix = "\r\n--" + boundary + "--\r\n";

            byte[] body = concat(prefix.getBytes(), fileBytes, suffix.getBytes());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.statusCode());
            result.put("body", response.body());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e) {
            throw new IllegalStateException("文件上传失败: " + e.getMessage(), e);
        }
    }

    private String execute(String url, String method, String body, String contentType, String headers) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            Map<String, String> headerMap = parseHeaders(headers);
            headerMap.forEach(requestBuilder::header);

            switch (method) {
                case "POST" -> requestBuilder.header("Content-Type", defaultContentType(contentType))
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                case "PUT" -> requestBuilder.header("Content-Type", defaultContentType(contentType))
                        .PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                case "DELETE" -> requestBuilder.DELETE();
                default -> requestBuilder.GET();
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.statusCode());
            result.put("headers", response.headers().map());
            result.put("body", response.body());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e) {
            throw new IllegalStateException("网络请求失败: " + e.getMessage(), e);
        }
    }

    private Map<String, String> parseHeaders(String headers) throws IOException {
        if (headers == null || headers.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(headers, new TypeReference<>() {
        });
    }

    private String defaultContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/json" : contentType;
    }

    private String extractFilename(String url) {
        String filename = url.substring(url.lastIndexOf('/') + 1);
        if (filename.isBlank()) {
            return "download.bin";
        }
        int queryIndex = filename.indexOf('?');
        if (queryIndex >= 0) {
            filename = filename.substring(0, queryIndex);
        }
        return filename.isBlank() ? "download.bin" : filename;
    }

    private byte[] concat(byte[] first, byte[] second, byte[] third) {
        byte[] result = new byte[first.length + second.length + third.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        System.arraycopy(third, 0, result, first.length + second.length, third.length);
        return result;
    }
}
