package com.sailv.terraform.analysis.debug;

import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.ProviderUsageKey;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderConfigMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderConfigPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateProviderPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateQuotaResourcePo;
import com.sailv.terraform.analysis.infrastructure.gateway.DatabaseTemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.TerraformAnalysisService;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import lombok.extern.log4j.Log4j2;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 本地调试入口。
 *
 * <p>用途：
 * <ul>
 *     <li>默认读取 docs 下的真实示例压缩包</li>
 *     <li>走真实的 service -> gateway -> mapper 装配路径，便于你在 IDE 里逐步断点</li>
 *     <li>最终直接打印两张表的待入库 PO 数据，而不只是打印领域结果</li>
 *     <li>provider 配置预制表改为内存版 mapper，避免本地 debug 依赖真实数据库</li>
 * </ul>
 *
 * <p>如果你要调别的文件，可以直接在 IDE 里改 DEFAULT_SOURCE，或者通过命令行传第一个参数覆盖。
 */
@Log4j2
public final class TerraformAnalysisDebugMain {

    private static final Path DEFAULT_SOURCE =
        Path.of("docs", "左邻智慧园区.zip").toAbsolutePath();

    private TerraformAnalysisDebugMain() {
    }

    public static void main(String[] args) throws Exception {
        Path sourcePath = resolveSourcePath(args);

        ProviderConfigMapper providerConfigMapper = new DebugProviderConfigMapper();
        RecordingTemplateProviderMapper templateProviderMapper = new RecordingTemplateProviderMapper();
        RecordingTemplateQuotaResourceMapper templateQuotaResourceMapper = new RecordingTemplateQuotaResourceMapper();
        DatabaseTemplateAnalysisGateway gateway = new DatabaseTemplateAnalysisGateway(
            providerConfigMapper,
            templateProviderMapper,
            templateQuotaResourceMapper
        );
        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(gateway);

        // 这里直接走 analyzeAndSave，便于你看到最终会进入两张表的 PO 数据。
        try (InputStream inputStream = Files.newInputStream(sourcePath)) {
            service.analyzeAndSave(
                "debug-template-id",
                inputStream,
                sourcePath.getFileName().toString(),
                defaultQuotaRules()
            );
        }

        printStoredData(sourcePath, templateProviderMapper.insertedProviders, templateQuotaResourceMapper.insertedResources);
    }

    private static Path resolveSourcePath(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        return DEFAULT_SOURCE;
    }

    private static QuotaCheckRule defaultQuotaRules() {
        return QuotaCheckRule.of(
                QuotaCheckRule.CloudServiceRule.of("ECS", "debug://quota/ecs", "instance_count", "cpu_count", "ram_count"),
                QuotaCheckRule.CloudServiceRule.of("VPC", "debug://quota/vpc", "vpc"),
                QuotaCheckRule.CloudServiceRule.of("EVS", "debug://quota/evs", "volumes", "gigabytes"),
                QuotaCheckRule.CloudServiceRule.of("ELB", "debug://quota/elb", "loadbalancer"),
                QuotaCheckRule.CloudServiceRule.of("NAT", "debug://quota/nat", "instance_count"),
                QuotaCheckRule.CloudServiceRule.of("SECGROUP", "debug://quota/", "instance_count"),
                QuotaCheckRule.CloudServiceRule.of("EIP", "debug://quota/eip", "publicIp"),
                QuotaCheckRule.CloudServiceRule.of("RDS", "debug://quota/rds", "instance"),
                QuotaCheckRule.CloudServiceRule.of("CCE", "debug://quota/cce", "cluster"),
                QuotaCheckRule.CloudServiceRule.of("DCS", "debug://quota/dcs", "instance"),
                QuotaCheckRule.CloudServiceRule.of("DMS", "debug://quota/dms", "rabbitmqInstance", "kafkaInstance", "rocketmqInstance"),
                QuotaCheckRule.CloudServiceRule.of("DDS", "debug://quota/dds", "Sharding", "ReplicaSet", "Single"),
                QuotaCheckRule.CloudServiceRule.of("CSS", "debug://quota/css", "rds"),
                QuotaCheckRule.CloudServiceRule.of("GauseDB", "debug://quota/gausedb", "instance"),
                QuotaCheckRule.CloudServiceRule.of("SFS_Turbo", "debug://quota/sfs-turbo", "shares")
        );
    }

    private static void printStoredData(
        Path sourcePath,
        List<TemplateProviderPo> templateProviders,
        List<TemplateQuotaResourcePo> templateResources
    ) {
        log.info("=== Terraform Analysis Debug ===");
        log.info("Source: {}", sourcePath);
        log.info("");
        log.info("t_mp_template_providers:");
        for (TemplateProviderPo provider : templateProviders) {
            log.info("  - id={}, templateId={}, providerName={}, providerType={}, createTime={}, updateTime={}",
                provider.getId(),
                provider.getTemplateId(),
                provider.getProviderName(),
                provider.getProviderType(),
                provider.getCreateTime(),
                provider.getUpdateTime()
            );
        }
        if (templateProviders.isEmpty()) {
            log.info("  (empty)");
        }
        log.info("");

        log.info("t_mp_template_resource:");
        for (TemplateQuotaResourcePo resource : templateResources) {
            log.info("  - id={}, templateId={}, resourceType={}, quotaType={}, quotaRequirement={}, createTime={}, updateTime={}",
                resource.getId(),
                resource.getTemplateId(),
                resource.getResourceType(),
                resource.getQuotaType(),
                resource.getQuotaRequirement(),
                resource.getCreateTime(),
                resource.getUpdateTime()
            );
        }
        if (templateResources.isEmpty()) {
            log.info("  (empty)");
        }
    }

    /**
     * 调试用的 provider 配置 mapper。
     *
     * <p>这层不是 gateway stub，而是给真实的 DatabaseTemplateAnalysisGateway 提供一个最小可运行的
     * 预制表实现，方便本地单步调试。
     */
    private static final class DebugProviderConfigMapper implements ProviderConfigMapper {

        private final Map<ProviderUsageKey, List<ProviderConfigPo>> definitions = new LinkedHashMap<>();

        private DebugProviderConfigMapper() {
            registerResource("huaweicloud", "ecs");
            registerResource("huaweicloud_compute_instance", "ecs");
            registerResource("huaweicloud_cce_node", "cce");
            registerResource("huaweicloud_cce_cluster", "cce");
            registerResource("huaweicloud", "vpc");
            registerResource("huaweicloud_vpc", "vpc");
            registerResource("huaweicloud", "vpc", 0);
            registerResource("huaweicloud_vpc", "vpc", 0);
            registerResource("huaweicloud", "secgroup");
            registerResource("huaweicloud_networking_secgroup", "secgroup");
            registerResource("huaweicloud", "secgroup", 0);
            registerResource("huaweicloud_networking_secgroup", "secgroup", 0);
            registerResource("huaweicloud", "evs");
            registerResource("huaweicloud_evs_volume", "evs");
            registerResource("huaweicloud", "elb");
            registerResource("huaweicloud_elb_loadbalancer", "elb");
            registerResource("huaweicloud", "nat");
            registerResource("huaweicloud_nat_gateway", "nat");
            registerResource("huaweicloud", "eip");
            registerResource("huaweicloud_vpc_eip", "eip");
            registerResource("huaweicloud_vpc_eip_associate", "eip", 0);
            registerResource("huaweicloud", "rds");
            registerResource("huaweicloud_rds_instance", "rds");
            registerResource("huaweicloud_rds_mysql_account", "rds", 0);
            registerResource("huaweicloud_rds_mysql_database", "rds", 0);
            registerResource("huaweicloud_rds_mysql_database_privilege", "rds", 0);
            registerResource("huaweicloud_dcs_instance", "dcs");
            registerResource("huaweicloud_cbr_vault", "cbr");
            registerResource("huaweicloud_cbr_policy", "cbr", 0);
            registerResource("huaweicloud_cbh_instance", "cbh");
            registerResource("huaweicloud_dms_kafka_instance", "dms", 1, "kafkaInstance");
            registerResource("huaweicloud_dms_rabbitmq_instance", "dms", 1, "rabbitmqInstance");
            registerResource("huaweicloud_dds_instance", "dds");
            registerResource("huaweicloud_gaussdb_mysql_instance", "gausedb");
            registerResource("huaweicloud_css_cluster", "css");
            registerResource("huaweicloud_sfs_file_system", "sfs_turbo");

            // docs 示例包里真实存在的一批 data source。
            // 这些类型不参与配额入库，但应该出现在 t_mp_template_providers 中，
            // providerType 需要明确标成 data。
            registerDatasource("huaweicloud_availability_zones");
            registerDatasource("huaweicloud_compute_flavors");
            registerDatasource("huaweicloud_rds_flavors");
            registerDatasource("huaweicloud_rds_storage_types");
            registerDatasource("huaweicloud_images_image");
            registerDatasource("huaweicloud_koogallery_assets");
            registerDatasource("huaweicloud_cbh_flavors");
            registerDatasource("huaweicloud_dbss_flavors");
            registerDatasource("huaweicloud_dcs_flavors");
            registerDatasource("huaweicloud_gaussdb_mysql_flavors");
            registerDatasource("huaweicloud_identity_projects");
        }

        @Override
        public List<ProviderConfigPo> selectByProviderUsages(Collection<ProviderUsageKey> providerUsages) {
            if (providerUsages == null || providerUsages.isEmpty()) {
                return List.of();
            }
            List<ProviderConfigPo> resolved = new ArrayList<>();
            for (ProviderUsageKey usage : providerUsages) {
                if (usage == null || usage.getProviderName() == null || usage.getProviderName().isBlank() || usage.getProviderType() == null) {
                    continue;
                }
                resolved.addAll(resolve(usage));
            }
            return resolved;
        }

        @Override
        public List<ProviderConfigPo> selectExistingProviderUsages(Collection<ProviderUsageKey> providerUsages) {
            if (providerUsages == null || providerUsages.isEmpty()) {
                return List.of();
            }
            List<ProviderConfigPo> existing = new ArrayList<>();
            for (ProviderUsageKey usage : providerUsages) {
                if (usage == null || usage.getProviderName() == null || usage.getProviderName().isBlank() || usage.getProviderType() == null) {
                    continue;
                }
                if (!resolve(usage).isEmpty()) {
                    existing.add(new ProviderConfigPo()
                        .setProviderName(usage.getProviderName())
                        .setProviderType(usage.getProviderType().getDbValue()));
                }
            }
            return existing;
        }

        private void registerResource(String providerName, String resourceType) {
            registerResource(providerName, resourceType, 1, null);
        }

        private void registerResource(String providerName, String resourceType, int primaryQuotaSubject) {
            registerResource(providerName, resourceType, primaryQuotaSubject, null);
        }

        private void registerResource(
            String providerName,
            String resourceType,
            int primaryQuotaSubject,
            String quotaTypeHint
        ) {
            ProviderConfigPo po = new ProviderConfigPo()
                .setProviderName(providerName)
                .setQuotaResourceType(resourceType)
                .setQuotaTypeHint(quotaTypeHint)
                .setProviderType("resource")
                .setIsPrimaryQuotaSubject(primaryQuotaSubject)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
            definitions.computeIfAbsent(new ProviderUsageKey(providerName, ProviderType.RESOURCE), ignored -> new ArrayList<>()).add(po);
        }

        private void registerDatasource(String providerName) {
            ProviderConfigPo po = new ProviderConfigPo()
                .setProviderName(providerName)
                .setQuotaResourceType(null)
                .setProviderType("data")
                .setIsPrimaryQuotaSubject(0)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
            definitions.computeIfAbsent(new ProviderUsageKey(providerName, ProviderType.DATA), ignored -> new ArrayList<>()).add(po);
        }

        private List<ProviderConfigPo> resolve(ProviderUsageKey usage) {
            List<ProviderConfigPo> exact = definitions.get(usage);
            if (exact != null && !exact.isEmpty()) {
                return exact;
            }

            // 调试阶段不可能把 docs 包里所有动作都手工列完。
            // 因此这里给一个“可断点观察”的兜底映射：
            // - providerName 现在就是 Terraform 类型本身，例如 huaweicloud_cce_node
            // - resourceType 尽量映射成平台通用资源类型，而不是直接回写原 Terraform 类型
            String normalizedProvider = normalize(usage.getProviderName());
            if (normalizedProvider == null) {
                return List.of();
            }
            if (!normalizedProvider.startsWith("huaweicloud_") && !"huaweicloud".equals(normalizedProvider)) {
                return List.of();
            }

            if (usage.getProviderType() == ProviderType.DATA) {
                return List.of(new ProviderConfigPo()
                    .setProviderName(normalizedProvider)
                    .setQuotaResourceType(null)
                    .setProviderType("data")
                    .setIsPrimaryQuotaSubject(0));
            }

            String resourceType = inferResourceType(normalizedProvider);
            if (resourceType == null) {
                return List.of();
            }
            return List.of(new ProviderConfigPo()
                .setProviderName(normalizedProvider)
                .setQuotaResourceType(resourceType)
                .setProviderType("resource")
                .setIsPrimaryQuotaSubject(1)); // Defaulting to primary resource in debug logic when inferred
        }

        private String inferResourceType(String providerName) {
            String normalized = providerName.toLowerCase(Locale.ROOT);
            if (normalized.contains("compute_instance")) {
                return "ecs";
            }
            if (normalized.contains("cce_")) {
                return "cce";
            }
            if (normalized.contains("evs") || normalized.contains("volume")) {
                return "evs";
            }
            if (normalized.contains("loadbalancer") || normalized.contains("elb")) {
                return "elb";
            }
            if (normalized.contains("nat_gateway")) {
                return "nat";
            }
            if (normalized.contains("eip") || normalized.contains("bandwidth")) {
                return "eip";
            }
            if (normalized.contains("secgroup")) {
                return "secgroup";
            }
            if (normalized.contains("rds")) {
                return "rds";
            }
            if (normalized.contains("subnet") || normalized.contains("vpc")) {
                return "vpc";
            }
            return null;
        }

        private String normalize(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private static final class RecordingTemplateProviderMapper implements TemplateProviderMapper {
        private final List<TemplateProviderPo> insertedProviders = new ArrayList<>();

        @Override
        public int insertBatch(Collection<TemplateProviderPo> providers) {
            if (providers == null || providers.isEmpty()) {
                return 0;
            }
            insertedProviders.addAll(providers);
            return providers.size();
        }
    }

    private static final class RecordingTemplateQuotaResourceMapper implements TemplateQuotaResourceMapper {
        private final List<TemplateQuotaResourcePo> insertedResources = new ArrayList<>();

        @Override
        public int insertBatch(Collection<TemplateQuotaResourcePo> resources) {
            if (resources == null || resources.isEmpty()) {
                return 0;
            }
            insertedResources.addAll(resources);
            return resources.size();
        }
    }
}
