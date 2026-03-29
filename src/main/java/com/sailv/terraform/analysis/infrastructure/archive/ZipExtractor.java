package com.sailv.terraform.analysis.infrastructure.archive;

import com.sailv.terraform.analysis.application.TemplateSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * zip 解压器。
 *
 * <p>这个类只做两件事：安全解压、返回解压后的目录。
 * 根模块识别和递归扫描由应用服务负责。
 */
public class ZipExtractor {

    public Path extract(TemplateSource source, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);
        try (InputStream inputStream = source.openStream(); ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path targetPath = targetDirectory.resolve(entry.getName()).normalize();
                // 防止 zip slip，确保解压结果始终落在目标目录下。
                if (!targetPath.startsWith(targetDirectory)) {
                    throw new IOException("Blocked zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                    continue;
                }
                Files.createDirectories(targetPath.getParent());
                Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return targetDirectory;
    }
}
