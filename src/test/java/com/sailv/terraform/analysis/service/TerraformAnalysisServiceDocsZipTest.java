package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformAnalysisServiceDocsZipTest {

    @Test
    void shouldAnalyzeProductionArchiveUnderDocs() throws Exception {
        Path archive = Path.of("docs", "huaweicloud-app-orchestration-hcl-master-dev.zip").toAbsolutePath();
        assertTrue(Files.exists(archive), "docs zip archive is required for this regression test");

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result = service.analyze(
            "template-docs-zip",
            TemplateSource.fromPath(archive),
            List.of(
                new QuotaCheckRule("huaweicloud_compute_instance", "https://quota.internal/ecs", "instance_count"),
                new QuotaCheckRule("huaweicloud_vpc", "https://quota.internal/vpc", "instance_count"),
                new QuotaCheckRule("huaweicloud_vpc_subnet", "https://quota.internal/vpc-subnet", "instance_count"),
                new QuotaCheckRule("huaweicloud_networking_secgroup", "https://quota.internal/secgroup", "instance_count")
            )
        );

        Set<String> providerNames = result.providers().stream()
            .map(provider -> provider.providerName())
            .collect(java.util.stream.Collectors.toSet());
        Set<String> quotaResourceTypes = result.quotaResources().stream()
            .map(resource -> resource.resourceType())
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(providerNames.contains("huaweicloud_compute_instance"));
        assertTrue(providerNames.contains("huaweicloud_cce_node"));
        assertFalse(result.providers().isEmpty(), "production archive should resolve at least one provider");
        assertTrue(quotaResourceTypes.contains("huaweicloud_compute_instance"));
        assertTrue(quotaResourceTypes.contains("huaweicloud_vpc"));
        assertTrue(quotaResourceTypes.contains("huaweicloud_vpc_subnet"));
        assertTrue(quotaResourceTypes.contains("huaweicloud_networking_secgroup"));
    }

    /**
     * 回归测试里不接数据库，直接把动作名映射成同名 resourceType。
     *
     * <p>这里的重点不是验证数据库层，而是确认真实压缩包中的 Terraform 文件
     * 能被完整扫描并进入领域分析流程。
     */
    private static final class StubTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public Optional<ProviderActionDefinition> findByProviderNameAndActionName(TerraformAction action) {
            return Optional.of(new ProviderActionDefinition(
                action.providerName(),
                action.providerName(),
                action.providerName(),
                action.kind() == TerraformAction.Kind.DATA_SOURCE ? "datasource" : "resource",
                "instance_count"
            ));
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 回归测试只验证 analyze，不验证数据库写入。
        }
    }
}
