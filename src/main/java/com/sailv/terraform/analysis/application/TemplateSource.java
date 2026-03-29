package com.sailv.terraform.analysis.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * 模板输入源抽象。
 *
 * <p>这个类把磁盘文件、上传字节流、输入流统一封装成同一种输入对象，
 * 这样应用层只需要处理一种调用方式。
 *
 * <p>迁移到内网项目时通常不需要改这里，直接复用即可。
 */
public final class TemplateSource {
    private final String fileName;
    private final Path path;
    private final byte[] content;

    private TemplateSource(String fileName, Path path, byte[] content) {
        this.fileName = requireText(fileName, "fileName");
        this.path = path;
        this.content = content == null ? null : content.clone();
    }

    public static TemplateSource fromPath(Path path) {
        Objects.requireNonNull(path, "path cannot be null");
        return new TemplateSource(path.getFileName().toString(), path, null);
    }

    public static TemplateSource fromBytes(String fileName, byte[] content) {
        Objects.requireNonNull(content, "content cannot be null");
        return new TemplateSource(fileName, null, content);
    }

    public static TemplateSource fromInputStream(String fileName, InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream cannot be null");
        return fromBytes(fileName, inputStream.readAllBytes());
    }

    public String fileName() {
        return fileName;
    }

    public boolean isPathBacked() {
        return path != null;
    }

    public Path path() {
        return path;
    }

    public InputStream openStream() throws IOException {
        if (path != null) {
            return Files.newInputStream(path);
        }
        return new ByteArrayInputStream(content);
    }

    public String extension() {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".tf.json")) {
            return ".tf.json";
        }
        if (normalized.endsWith(".tf")) {
            return ".tf";
        }
        if (normalized.endsWith(".zip")) {
            return ".zip";
        }
        return "";
    }

    public boolean isZip() {
        return ".zip".equals(extension());
    }

    public boolean isTerraformFile() {
        return ".tf".equals(extension()) || ".tf.json".equals(extension());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return trimmed;
    }
}
