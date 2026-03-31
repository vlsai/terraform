package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformAnalysisServiceProviderValidationTest {

    @Test
    void shouldSkipProviderWhenPresetTableDoesNotContainIt() throws Exception {
        byte[] content = """
            provider "unknowncloud" {}
            """
            .getBytes(StandardCharsets.UTF_8);

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new EmptyTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            result = service.analyze("template-provider-validation", inputStream, "main.tf", new QuotaCheckRule());
        }

        assertTrue(result.getProviders().isEmpty());
    }

    @Test
    void shouldUnifyProviderTypeWhenPresetTableContainsResourceAndData() throws Exception {
        byte[] content = """
            data "huaweicloud_compute_instance" "lookup" {}

            resource "huaweicloud_compute_instance" "create" {
              count = 1
            }
            """
            .getBytes(StandardCharsets.UTF_8);

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new MixedTypeTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            result = service.analyze(
                "template-provider-unify",
                inputStream,
                "main.tf",
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("ECS", "https://quota.internal/ecs", "instance")
                )
            );
        }

        assertEquals(1, result.getProviders().size());
        assertEquals("huaweicloud_compute_instance", result.getProviders().getFirst().getProviderName());
        assertEquals("resource", result.getProviders().getFirst().getProviderType());
        assertEquals(1, result.getQuotaResources().size());
        assertEquals(1, result.getQuotaResources().getFirst().getQuotaRequirement());
    }

    private static final class EmptyTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return List.of();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 测试只验证分析结果。
        }
    }

    private static final class MixedTypeTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return List.of(
                new ProviderAction()
                    .setProviderName("huaweicloud_compute_instance")
                    .setActionName("huaweicloud_compute_instance:data")
                    .setResourceType(null)
                    .setProviderType(ProviderAction.ProviderType.DATA),
                new ProviderAction()
                    .setProviderName("huaweicloud_compute_instance")
                    .setActionName("huaweicloud_compute_instance:resource")
                    .setResourceType("ecs")
                    .setProviderType(ProviderAction.ProviderType.RESOURCE)
            );
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 测试只验证分析结果。
        }
    }
}
