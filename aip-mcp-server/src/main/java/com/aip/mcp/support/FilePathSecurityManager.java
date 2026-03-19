package com.aip.mcp.support;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class FilePathSecurityManager {

    @Value("${file.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${general.file.storage.path:./storage/files}")
    private String generalFilePath;

    @Value("${project.workspace.path:./workspace}")
    private String projectWorkspacePath;

    @Value("${external.export.path:/Users/qijian/Desktop/爱化身}")
    private String externalExportPath;

    private Path generalFileRoot;
    private Path projectWorkspaceRoot;
    private Path externalExportRoot;

    @PostConstruct
    public void init() {
        try {
            generalFileRoot = Paths.get(generalFilePath).toAbsolutePath().normalize();
            projectWorkspaceRoot = Paths.get(projectWorkspacePath).toAbsolutePath().normalize();
            externalExportRoot = Paths.get(externalExportPath).toAbsolutePath().normalize();

            Files.createDirectories(generalFileRoot);
            Files.createDirectories(projectWorkspaceRoot);
            Files.createDirectories(externalExportRoot);
        }
        catch (IOException e) {
            throw new IllegalStateException("初始化文件安全路径失败", e);
        }
    }

    public Path validateAndNormalizePath(String path, String allowedType) {
        Path normalizedPath = Paths.get(path).toAbsolutePath().normalize();
        if (!securityEnabled) {
            return normalizedPath;
        }

        Path allowedRoot = getAllowedRoot(allowedType);
        if (allowedRoot == null) {
            throw new SecurityException("未知的路径类型: " + allowedType);
        }
        if (!normalizedPath.startsWith(allowedRoot)) {
            throw new SecurityException("路径不允许: " + normalizedPath + "，允许根目录: " + allowedRoot);
        }
        return normalizedPath;
    }

    public String generateSafeRelativePath(String originalFilename) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String extension = "";
        int lastDot = originalFilename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < originalFilename.length() - 1) {
            extension = originalFilename.substring(lastDot);
        }
        return timestamp + "_" + random + extension;
    }

    private Path getAllowedRoot(String type) {
        return switch (type) {
            case "general", "file" -> generalFileRoot;
            case "project" -> projectWorkspaceRoot;
            case "export" -> externalExportRoot;
            default -> null;
        };
    }

    public Path getGeneralFileRoot() {
        return generalFileRoot;
    }

    public Path getProjectWorkspaceRoot() {
        return projectWorkspaceRoot;
    }

    public Path getExternalExportRoot() {
        return externalExportRoot;
    }

    public boolean isInGeneralFileRoot(Path path) {
        return path.toAbsolutePath().normalize().startsWith(generalFileRoot);
    }

    public boolean isInExternalExportRoot(Path path) {
        return path.toAbsolutePath().normalize().startsWith(externalExportRoot);
    }
}
