package com.sailv.terraform.analysis.application;

import com.sailv.terraform.analysis.application.model.DiscoveredModuleReference;
import com.sailv.terraform.analysis.application.model.DiscoveredTemplateStructure;
import com.sailv.terraform.analysis.application.model.ParsedTerraformFile;
import com.sailv.terraform.analysis.domain.model.AnalysisWarning;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.port.ProviderActionQueryPort;
import com.sailv.terraform.analysis.domain.service.TemplateAnalysisDomainService;
import com.sailv.terraform.analysis.infrastructure.archive.ZipExtractor;
import com.sailv.terraform.analysis.infrastructure.parser.HclTerraformFileParser;
import com.sailv.terraform.analysis.infrastructure.parser.JsonTerraformFileParser;
import com.sailv.terraform.analysis.infrastructure.parser.TerraformFileParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Terraform 模板分析入口。
 *
 * <p>职责划分：
 * <ul>
 *     <li>识别输入是 `.zip`、`.tf` 还是 `.tf.json`</li>
 *     <li>如果是 zip，则安全解压到临时目录</li>
 *     <li>递归扫描根模块和本地 module</li>
 *     <li>把“解析结果”交给领域服务，生成最终可落库对象</li>
 * </ul>
 *
 * <p>迁移到内网环境时，通常只需要替换 {@link ProviderActionQueryPort} 的实现，
 * 让它对接你们现有的 DAO / Repository。
 */
public class TerraformTemplateAnalyzeService {

    private final List<TerraformFileParser> parsers;
    private final ZipExtractor zipExtractor;
    private final TemplateAnalysisDomainService domainService;

    public TerraformTemplateAnalyzeService(ProviderActionQueryPort providerActionQueryPort) {
        this(
            List.of(new JsonTerraformFileParser(), new HclTerraformFileParser()),
            new ZipExtractor(),
            new TemplateAnalysisDomainService(providerActionQueryPort)
        );
    }

    public TerraformTemplateAnalyzeService(
        List<TerraformFileParser> parsers,
        ZipExtractor zipExtractor,
        TemplateAnalysisDomainService domainService
    ) {
        this.parsers = List.copyOf(parsers);
        this.zipExtractor = zipExtractor;
        this.domainService = domainService;
    }

    public TemplateAnalysisResult analyze(String templateId, TemplateSource source, Collection<QuotaCheckRule> quotaRules)
        throws IOException {
        // 这里只接受当前需求中明确支持的三种 Terraform 输入。
        if (!source.isZip() && !source.isTerraformFile()) {
            throw new IllegalArgumentException("Unsupported template source: " + source.fileName());
        }

        Path tempWorkspace = null;
        try {
            List<AnalysisWarning> bootstrapWarnings = new ArrayList<>();
            List<Path> moduleRoots;

            if (source.isZip()) {
                // zip 包统一解压到临时目录，后续流程全部走基于 Path 的扫描逻辑。
                tempWorkspace = Files.createTempDirectory("terraform-analysis-zip-");
                Path extracted = zipExtractor.extract(source, tempWorkspace.resolve("unzipped"));
                moduleRoots = determineStartingModuleDirs(extracted, bootstrapWarnings);
            } else if (source.isPathBacked()) {
                // 已经在磁盘上的 `.tf` / `.tf.json` 文件，直接使用其父目录作为模块目录。
                moduleRoots = List.of(source.path().toAbsolutePath().normalize().getParent());
            } else {
                // 流式输入先物化到临时文件，否则 parser 无法统一按 Path 处理。
                tempWorkspace = Files.createTempDirectory("terraform-analysis-file-");
                Path materialized = tempWorkspace.resolve(source.fileName());
                try (InputStream inputStream = source.openStream()) {
                    Files.write(materialized, inputStream.readAllBytes());
                }
                moduleRoots = List.of(materialized.getParent());
            }

            DiscoveredTemplateStructure discovered = discoverStructure(moduleRoots, bootstrapWarnings);
            return domainService.analyze(templateId, discovered, quotaRules);
        } finally {
            if (tempWorkspace != null) {
                deleteRecursively(tempWorkspace);
            }
        }
    }

    private DiscoveredTemplateStructure discoverStructure(List<Path> moduleRoots, List<AnalysisWarning> bootstrapWarnings)
        throws IOException {
        DiscoveredTemplateStructure.Builder builder = DiscoveredTemplateStructure.builder().addWarnings(bootstrapWarnings);

        if (moduleRoots.isEmpty()) {
            builder.addWarnings(List.of(new AnalysisWarning(
                "NO_TERRAFORM_FILES",
                "No Terraform files were found in the provided source",
                ""
            )));
            return builder.build();
        }

        Deque<Path> toVisit = new ArrayDeque<>(moduleRoots);
        Set<Path> visited = new HashSet<>();

        while (!toVisit.isEmpty()) {
            // 用队列遍历本地 module，避免重复解析已经访问过的目录。
            Path moduleDir = toVisit.removeFirst().toRealPath().normalize();
            if (!visited.add(moduleDir)) {
                continue;
            }
            List<Path> terraformFiles = listDirectTerraformFiles(moduleDir);
            if (terraformFiles.isEmpty()) {
                builder.addWarnings(List.of(new AnalysisWarning(
                    "EMPTY_MODULE_DIRECTORY",
                    "No Terraform files were found in module directory " + moduleDir,
                    moduleDir.toString()
                )));
                continue;
            }
            for (Path terraformFile : terraformFiles) {
                // parser 只负责“发现 provider/resource/module”，不负责数据库映射。
                ParsedTerraformFile parsed = parserFor(terraformFile).parse(terraformFile);
                builder.addProviders(parsed.providerBlockNames())
                    .addRequiredProviders(parsed.requiredProviderNames())
                    .addActions(parsed.actions())
                    .addWarnings(parsed.warnings());
                enqueueLocalModules(moduleDir, parsed.moduleReferences(), builder, toVisit);
            }
        }
        return builder.build();
    }

    private void enqueueLocalModules(
        Path currentModuleDir,
        List<DiscoveredModuleReference> moduleReferences,
        DiscoveredTemplateStructure.Builder builder,
        Deque<Path> toVisit
    ) {
        for (DiscoveredModuleReference moduleReference : moduleReferences) {
            if (!isLocalModuleSource(moduleReference.rawSource())) {
                // 远程 module 不在库内拉取，避免把网络和认证逻辑硬耦合到此库。
                builder.addWarnings(List.of(new AnalysisWarning(
                    "REMOTE_MODULE_SKIPPED",
                    "Skipped remote module source " + moduleReference.rawSource(),
                    moduleReference.sourceFile()
                )));
                continue;
            }
            Path resolved = currentModuleDir.resolve(moduleReference.rawSource()).normalize();
            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                toVisit.addLast(resolved);
                continue;
            }
            builder.addWarnings(List.of(new AnalysisWarning(
                "MODULE_NOT_FOUND",
                "Local module directory does not exist: " + moduleReference.rawSource(),
                moduleReference.sourceFile()
            )));
        }
    }

    private List<Path> determineStartingModuleDirs(Path extractedRoot, List<AnalysisWarning> warnings) throws IOException {
        // 很多 zip 上传包会额外包一层目录，先做一次单层折叠，提升根模块识别率。
        Path collapsedRoot = collapseSingleNestedRoot(extractedRoot);
        if (hasDirectTerraformFiles(collapsedRoot)) {
            return List.of(collapsedRoot);
        }

        List<Path> discoveredRoots;
        try (Stream<Path> stream = Files.walk(collapsedRoot)) {
            discoveredRoots = stream
                .filter(Files::isDirectory)
                .filter(path -> {
                    try {
                        return hasDirectTerraformFiles(path);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .sorted()
                .toList();
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }

        if (!discoveredRoots.isEmpty()) {
            // 根目录本身没有 Terraform 文件时，退化为扫描所有疑似模块目录。
            warnings.add(new AnalysisWarning(
                "ROOT_MODULE_GUESSED",
                "No Terraform files were found at archive root; falling back to discovered Terraform directories",
                collapsedRoot.toString()
            ));
        }
        return discoveredRoots;
    }

    private Path collapseSingleNestedRoot(Path root) throws IOException {
        Path current = root;
        while (!hasDirectTerraformFiles(current)) {
            List<Path> childDirectories;
            try (Stream<Path> stream = Files.list(current)) {
                childDirectories = stream.filter(Files::isDirectory).sorted().toList();
            }
            if (childDirectories.size() != 1) {
                return current;
            }
            current = childDirectories.getFirst();
        }
        return current;
    }

    private List<Path> listDirectTerraformFiles(Path moduleDir) throws IOException {
        try (Stream<Path> stream = Files.list(moduleDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(this::isTerraformFile)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private boolean hasDirectTerraformFiles(Path directory) throws IOException {
        return !listDirectTerraformFiles(directory).isEmpty();
    }

    private TerraformFileParser parserFor(Path file) {
        return parsers.stream()
            .filter(parser -> parser.supports(file))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No parser available for file " + file));
    }

    private boolean isTerraformFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tf") || name.endsWith(".tf.json");
    }

    private boolean isLocalModuleSource(String rawSource) {
        // 仅把明显的本地路径当作递归解析目标；带协议或 git:: 的 source 统一视为远程。
        String normalized = rawSource.trim().replace('\\', '/');
        if (normalized.startsWith("./") || normalized.startsWith("../") || normalized.startsWith("/")) {
            return true;
        }
        if (normalized.startsWith("git::") || normalized.contains("://")) {
            return false;
        }
        long slashCount = normalized.chars().filter(character -> character == '/').count();
        return slashCount < 2;
    }

    private void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
