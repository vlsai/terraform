package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformAnalysisServiceDocsZipTest {

    @Test
    void shouldAnalyzeProductionArchiveUnderDocs() throws Exception {
        Path archive = Path.of("docs", "huaweicloud-app-orchestration-hcl-master-dev.zip").toAbsolutePath();
        assertTrue(Files.exists(archive), "docs zip archive is required for this regression test");

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (InputStream inputStream = Files.newInputStream(archive)) {
            result = service.analyze(
                "template-docs-zip",
                inputStream,
                archive.getFileName().toString(),
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("ECS", "https://quota.internal/ecs", "instance"),
                    QuotaCheckRule.CloudServiceRule.of("VPC", "https://quota.internal/vpc", "VPC"),
                    QuotaCheckRule.CloudServiceRule.of("SECGROUP", "https://quota.internal/secgroup", "instance")
                )
            );
        }

        Set<String> providerNames = result.getProviders().stream()
            .map(provider -> provider.getProviderName())
            .collect(java.util.stream.Collectors.toSet());
        Set<String> quotaResourceTypes = result.getQuotaResources().stream()
            .map(resource -> resource.getResourceType())
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(providerNames.contains("huaweicloud_compute_instance"));
        assertTrue(providerNames.contains("huaweicloud_cce_node"));
        assertFalse(result.getProviders().isEmpty(), "production archive should resolve at least one provider");
        assertTrue(quotaResourceTypes.contains("ECS"));
        assertTrue(quotaResourceTypes.contains("VPC"));
        assertTrue(quotaResourceTypes.contains("SECGROUP"));
    }

    /**
     * 回归测试里不接数据库，直接把动作名映射成同名 resourceType。
     *
     * <p>这里的重点不是验证数据库层，而是确认真实压缩包中的 Terraform 文件
     * 能被完整扫描并进入领域分析流程。
     */
    private static final class StubTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return actions.stream()
                .map(action -> new ProviderAction(
                    action.getProviderName(),
                    action.getProviderName() + ":permission",
                    action.getProviderName().contains("compute_instance") ? "ecs"
                        : action.getProviderName().contains("subnet") || action.getProviderName().contains("vpc") ? "vpc"
                        : action.getProviderName().contains("secgroup") ? "secgroup"
                        : action.getProviderName().contains("cce") ? "cce"
                        : action.getProviderName(),
                    action.getProviderType() == TerraformAction.ProviderType.DATA_SOURCE ? ProviderAction.ProviderType.DATA : ProviderAction.ProviderType.RESOURCE
                ))
                .distinct()
                .toList();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 回归测试只验证 analyze，不验证数据库写入。
        }
    }
}
