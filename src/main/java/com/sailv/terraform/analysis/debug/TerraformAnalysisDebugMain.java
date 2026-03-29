package com.sailv.terraform.analysis.debug;

import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderActionMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionLookupPo;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateProviderPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateQuotaResourcePo;
import com.sailv.terraform.analysis.infrastructure.gateway.DatabaseTemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.TerraformAnalysisService;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;

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
 *     <li>provider/action 预制表改为内存版 mapper，避免本地 debug 依赖真实数据库</li>
 * </ul>
 *
 * <p>如果你要调别的文件，可以直接在 IDE 里改 DEFAULT_SOURCE，或者通过命令行传第一个参数覆盖。
 */
public final class TerraformAnalysisDebugMain {

    private static final Path DEFAULT_SOURCE =
        Path.of("docs", "huaweicloud-app-orchestration-hcl-master-dev.zip").toAbsolutePath();

    private TerraformAnalysisDebugMain() {
    }

    public static void main(String[] args) throws Exception {
        Path sourcePath = resolveSourcePath(args);

        ProviderActionMapper providerActionMapper = new DebugProviderActionMapper();
        RecordingTemplateProviderMapper templateProviderMapper = new RecordingTemplateProviderMapper();
        RecordingTemplateQuotaResourceMapper templateQuotaResourceMapper = new RecordingTemplateQuotaResourceMapper();
        DatabaseTemplateAnalysisGateway gateway = new DatabaseTemplateAnalysisGateway(
            providerActionMapper,
            templateProviderMapper,
            templateQuotaResourceMapper
        );
        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(gateway);

        // 这里直接走 analyzeAndSave，便于你看到最终会进入两张表的 PO 数据。
        service.analyzeAndSave(
            "debug-template-id",
            TemplateSource.fromPath(sourcePath),
            defaultQuotaRules()
        );

        printStoredData(sourcePath, templateProviderMapper.insertedProviders, templateQuotaResourceMapper.insertedResources);
    }

    private static Path resolveSourcePath(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        return DEFAULT_SOURCE;
    }

    private static List<QuotaCheckRule> defaultQuotaRules() {
        return List.of(
            new QuotaCheckRule("ecs", "debug://quota/ecs", "instance_count"),
            new QuotaCheckRule("vpc", "debug://quota/vpc", "instance_count"),
            new QuotaCheckRule("evs", "debug://quota/evs", "instance_count"),
            new QuotaCheckRule("elb", "debug://quota/elb", "instance_count"),
            new QuotaCheckRule("nat", "debug://quota/nat", "instance_count"),
            new QuotaCheckRule("secgroup", "debug://quota/secgroup", "instance_count"),
            new QuotaCheckRule("eip", "debug://quota/eip", "instance_count"),
            new QuotaCheckRule("rds", "debug://quota/rds", "instance_count")
        );
    }

    private static void printStoredData(
        Path sourcePath,
        List<TemplateProviderPo> templateProviders,
        List<TemplateQuotaResourcePo> templateResources
    ) {
        System.out.println("=== Terraform Analysis Debug ===");
        System.out.println("Source: " + sourcePath);
        System.out.println();

        System.out.println("t_mp_template_providers:");
        for (TemplateProviderPo provider : templateProviders) {
            System.out.println("  - id=" + provider.getId()
                + ", templateId=" + provider.getTemplateId()
                + ", providerName=" + provider.getProviderName()
                + ", providerType=" + provider.getProviderType()
                + ", createTime=" + provider.getCreateTime()
                + ", updateTime=" + provider.getUpdateTime());
        }
        if (templateProviders.isEmpty()) {
            System.out.println("  (empty)");
        }
        System.out.println();

        System.out.println("t_mp_template_resource:");
        for (TemplateQuotaResourcePo resource : templateResources) {
            System.out.println("  - id=" + resource.getId()
                + ", templateId=" + resource.getTemplateId()
                + ", resourceType=" + resource.getResourceType()
                + ", quotaType=" + resource.getQuotaType()
                + ", quotaRequirement=" + resource.getQuotaRequirement()
                + ", createTime=" + resource.getCreateTime()
                + ", updateTime=" + resource.getUpdateTime());
        }
        if (templateResources.isEmpty()) {
            System.out.println("  (empty)");
        }
    }

    /**
     * 调试用的 provider_action mapper。
     *
     * <p>这层不是 gateway stub，而是给真实的 DatabaseTemplateAnalysisGateway 提供一个最小可运行的
     * 预制表实现，方便本地单步调试。
     */
    private static final class DebugProviderActionMapper implements ProviderActionMapper {

        private final Map<String, ProviderActionPo> definitions = new LinkedHashMap<>();

        private DebugProviderActionMapper() {
            registerResource("huaweicloud", "huaweicloud_compute_instance", "ecs", "instance_count");
            registerResource("huaweicloud_compute_instance", "huaweicloud_compute_instance", "ecs", "instance_count");
            registerResource("huaweicloud_cce_node", "huaweicloud_cce_node", "cce", "instance_count");
            registerResource("huaweicloud_cce_cluster", "huaweicloud_cce_cluster", "cce", "instance_count");
            registerResource("huaweicloud", "huaweicloud_vpc", "vpc", "instance_count");
            registerResource("huaweicloud_vpc", "huaweicloud_vpc", "vpc", "instance_count");
            registerResource("huaweicloud", "huaweicloud_vpc_subnet", "vpc", "instance_count");
            registerResource("huaweicloud_vpc_subnet", "huaweicloud_vpc_subnet", "vpc", "instance_count");
            registerResource("huaweicloud", "huaweicloud_networking_secgroup", "secgroup", "instance_count");
            registerResource("huaweicloud_networking_secgroup", "huaweicloud_networking_secgroup", "secgroup", "instance_count");
            registerResource("huaweicloud", "huaweicloud_networking_secgroup_rule", "secgroup", "instance_count");
            registerResource("huaweicloud_networking_secgroup_rule", "huaweicloud_networking_secgroup_rule", "secgroup", "instance_count");
            registerResource("huaweicloud", "huaweicloud_evs_volume", "evs", "instance_count");
            registerResource("huaweicloud_evs_volume", "huaweicloud_evs_volume", "evs", "instance_count");
            registerResource("huaweicloud", "huaweicloud_elb_loadbalancer", "elb", "instance_count");
            registerResource("huaweicloud_elb_loadbalancer", "huaweicloud_elb_loadbalancer", "elb", "instance_count");
            registerResource("huaweicloud", "huaweicloud_nat_gateway", "nat", "instance_count");
            registerResource("huaweicloud_nat_gateway", "huaweicloud_nat_gateway", "nat", "instance_count");
            registerResource("huaweicloud", "huaweicloud_vpc_eip", "eip", "instance_count");
            registerResource("huaweicloud_vpc_eip", "huaweicloud_vpc_eip", "eip", "instance_count");
            registerResource("huaweicloud", "huaweicloud_rds_instance", "rds", "instance_count");
            registerResource("huaweicloud_rds_instance", "huaweicloud_rds_instance", "rds", "instance_count");

            // docs 示例包里真实存在的一批 datasource。
            // 这些类型不参与配额入库，但应该出现在 t_mp_template_providers 中，
            // providerType 需要明确标成 datasource。
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
        public ProviderActionPo selectByProviderNameAndActionName(String providerName, String actionName) {
            return resolve(providerName, actionName);
        }

        @Override
        public List<ProviderActionPo> selectByProviderActionKeys(Collection<ProviderActionLookupPo> lookupKeys) {
            if (lookupKeys == null || lookupKeys.isEmpty()) {
                return List.of();
            }
            List<ProviderActionPo> resolved = new ArrayList<>();
            for (ProviderActionLookupPo lookupKey : lookupKeys) {
                if (lookupKey == null) {
                    continue;
                }
                ProviderActionPo matched = resolve(lookupKey.getProviderName(), lookupKey.getActionName());
                if (matched != null) {
                    resolved.add(matched);
                }
            }
            return resolved;
        }

        @Override
        public List<String> selectExistingProviderNames(Collection<String> providerNames) {
            if (providerNames == null || providerNames.isEmpty()) {
                return List.of();
            }
            Set<String> existing = new LinkedHashSet<>();
            for (String providerName : providerNames) {
                if (providerName == null || providerName.isBlank()) {
                    continue;
                }
                boolean found = definitions.values().stream()
                    .map(ProviderActionPo::getProviderName)
                    .filter(Objects::nonNull)
                    .anyMatch(providerName::equals);
                if (found) {
                    existing.add(providerName);
                }
            }
            return List.copyOf(existing);
        }

        private void registerResource(
            String providerName,
            String actionName,
            String resourceType,
            String quotaType
        ) {
            ProviderActionPo po = new ProviderActionPo()
                .setProviderName(providerName)
                .setActionName(actionName)
                .setResourceType(resourceType)
                .setProviderType("resource")
                .setQuotaType(quotaType)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
            definitions.put(keyOf(providerName, actionName), po);
        }

        private void registerDatasource(String providerName) {
            ProviderActionPo po = new ProviderActionPo()
                .setProviderName(providerName)
                .setActionName(providerName)
                .setResourceType(null)
                .setProviderType("datasource")
                .setQuotaType(null)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
            definitions.put(keyOf(providerName, providerName), po);
        }

        private ProviderActionPo resolve(String providerName, String actionName) {
            ProviderActionPo exact = definitions.get(keyOf(providerName, providerName));
            if (exact == null) {
                exact = definitions.get(keyOf(providerName, actionName));
            }
            if (exact != null) {
                return exact;
            }

            // 调试阶段不可能把 docs 包里所有动作都手工列完。
            // 因此这里给一个“可断点观察”的兜底映射：
            // - providerName 现在就是 Terraform 类型本身，例如 huaweicloud_cce_node
            // - resourceType 尽量映射成平台通用资源类型，而不是直接回写原 Terraform 类型
            String normalizedProvider = normalize(providerName);
            if (normalizedProvider == null) {
                return null;
            }
            if (!normalizedProvider.startsWith("huaweicloud_") && !"huaweicloud".equals(normalizedProvider)) {
                return null;
            }

            String resourceType = inferResourceType(normalizedProvider);
            if (resourceType == null) {
                return null;
            }
            return new ProviderActionPo()
                .setProviderName(normalizedProvider)
                .setActionName(normalizedProvider)
                .setResourceType(resourceType)
                .setProviderType("resource")
                .setQuotaType("instance_count");
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

        private String keyOf(String providerName, String actionName) {
            return providerName + '\u0000' + actionName;
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
