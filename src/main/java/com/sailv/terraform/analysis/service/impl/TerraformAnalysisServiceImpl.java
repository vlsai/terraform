package com.sailv.terraform.analysis.service.impl;

import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformTemplateDomain;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.infrastructure.archive.ZipExtractor;
import com.sailv.terraform.analysis.infrastructure.parser.HclTerraformFileParser;
import com.sailv.terraform.analysis.infrastructure.parser.JsonTerraformFileParser;
import com.sailv.terraform.analysis.service.TerraformAnalysisService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Service 实现。
 *
 * <p>这里处理的是文件、压缩包、目录遍历、parser 选择等编排逻辑；
 * 领域映射则交给 {@link TerraformTemplateDomain} 自身完成。
 */
public class TerraformAnalysisServiceImpl implements TerraformAnalysisService {

    private static final Logger LOGGER = Logger.getLogger(TerraformAnalysisServiceImpl.class.getName());

    private final List<TerraformFileParser> parsers;
    private final ZipExtractor zipExtractor;
    private final TemplateAnalysisGateway templateAnalysisGateway;

    public TerraformAnalysisServiceImpl(TemplateAnalysisGateway templateAnalysisGateway) {
        this(
            List.of(new JsonTerraformFileParser(), new HclTerraformFileParser()),
            new ZipExtractor(),
            templateAnalysisGateway
        );
    }

    public TerraformAnalysisServiceImpl(
        List<TerraformFileParser> parsers,
        ZipExtractor zipExtractor,
        TemplateAnalysisGateway templateAnalysisGateway
    ) {
        this.parsers = List.copyOf(parsers);
        this.zipExtractor = zipExtractor;
        this.templateAnalysisGateway = templateAnalysisGateway;
    }

    @Override
    public TemplateAnalysisResult analyze(String templateId, TemplateSource source, Collection<QuotaCheckRule> quotaRules)
        throws IOException {
        if (!source.isZip() && !source.isTerraformFile()) {
            throw new IllegalArgumentException("Unsupported template source: " + source.fileName());
        }
        Path tempWorkspace = null;
        try {
            List<Path> moduleRoots;

            if (source.isZip()) {
                tempWorkspace = Files.createTempDirectory("terraform-analysis-zip-");
                Path extracted = zipExtractor.extract(source, tempWorkspace.resolve("unzipped"));
                moduleRoots = determineStartingModuleDirs(extracted);
            } else if (source.isPathBacked()) {
                moduleRoots = List.of(source.path().toAbsolutePath().normalize().getParent());
            } else {
                tempWorkspace = Files.createTempDirectory("terraform-analysis-file-");
                Path materialized = tempWorkspace.resolve(source.fileName());
                try (InputStream inputStream = source.openStream()) {
                    Files.write(materialized, inputStream.readAllBytes());
                }
                moduleRoots = List.of(materialized.getParent());
            }

            TerraformTemplateDomain templateDomain = discoverDomain(moduleRoots);
            return templateDomain.analyze(
                templateId,
                quotaRules,
                templateAnalysisGateway.findByProviderNameAndActionName(templateDomain.actions())
            );
        } finally {
            if (tempWorkspace != null) {
                deleteRecursively(tempWorkspace);
            }
        }
    }

    @Override
    public void save(TemplateAnalysisResult result) {
        templateAnalysisGateway.save(result);
    }

    private TerraformTemplateDomain discoverDomain(List<Path> moduleRoots) throws IOException {
        TerraformTemplateDomain templateDomain = new TerraformTemplateDomain();

        if (moduleRoots.isEmpty()) {
            LOGGER.warning("[NO_TERRAFORM_FILES] No Terraform files were found in the provided source");
            return templateDomain;
        }

        Deque<Path> toVisit = new ArrayDeque<>(moduleRoots);
        Set<Path> visited = new HashSet<>();

        while (!toVisit.isEmpty()) {
            Path moduleDir = toVisit.removeFirst().toRealPath().normalize();
            if (!visited.add(moduleDir)) {
                continue;
            }

            List<Path> terraformFiles = listDirectTerraformFiles(moduleDir);
            if (terraformFiles.isEmpty()) {
                LOGGER.info(() -> "[EMPTY_MODULE_DIRECTORY] No Terraform files were found in module directory "
                    + moduleDir);
                continue;
            }

            for (Path terraformFile : terraformFiles) {
                TerraformFileParser.ParseResult parseResult = parserFor(terraformFile).parse(terraformFile);
                templateDomain.merge(parseResult);
                enqueueLocalModules(moduleDir, parseResult.moduleReferences(), toVisit);
            }
        }
        return templateDomain;
    }

    private void enqueueLocalModules(
        Path currentModuleDir,
        List<TerraformFileParser.ModuleReference> moduleReferences,
        Deque<Path> toVisit
    ) {
        for (TerraformFileParser.ModuleReference moduleReference : moduleReferences) {
            if (!isLocalModuleSource(moduleReference.rawSource())) {
                LOGGER.info(() -> "[REMOTE_MODULE_SKIPPED] Skipped remote module source "
                    + moduleReference.rawSource());
                continue;
            }

            Path resolved = currentModuleDir.resolve(moduleReference.rawSource()).normalize();
            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                toVisit.addLast(resolved);
                continue;
            }

            LOGGER.warning(() -> "[MODULE_NOT_FOUND] Local module directory does not exist: "
                + moduleReference.rawSource());
        }
    }

    private List<Path> determineStartingModuleDirs(Path extractedRoot) throws IOException {
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
            LOGGER.info(() -> "[ROOT_MODULE_GUESSED] No Terraform files were found at archive root; "
                + "falling back to discovered Terraform directories under " + collapsedRoot);
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
