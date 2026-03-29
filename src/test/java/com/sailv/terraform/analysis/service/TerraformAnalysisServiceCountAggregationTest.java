package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerraformAnalysisServiceCountAggregationTest {

    @Test
    void shouldExpandQuotaResourceRowsByResolvedCount() throws Exception {
        TemplateSource source = TemplateSource.fromBytes(
            "main.tf",
            """
                locals {
                  ecs_count = 3
                }

                provider "huaweicloud" {}

                resource "huaweicloud_compute_instance" "web" {
                  count = local.ecs_count
                }

                resource "huaweicloud_compute_instance" "job" {
                  count = 2
                }
                """
                .getBytes(StandardCharsets.UTF_8)
        );

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result = service.analyze(
            "template-counts",
            source,
            List.of(new QuotaCheckRule("ecs", "https://quota.internal/ecs", "instance_count"))
        );

        assertEquals(1, result.providers().size());
        assertEquals("huaweicloud_compute_instance", result.providers().getFirst().providerName());
        assertEquals("resource", result.providers().getFirst().providerType());
        assertEquals(1, result.quotaResources().size());
        assertEquals("ecs", result.quotaResources().getFirst().resourceType());
        assertEquals("instance_count", result.quotaResources().getFirst().quotaType());
        assertEquals(5, result.quotaResources().getFirst().quotaRequirement());
    }

    private static final class StubTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public Optional<ProviderActionDefinition> findByProviderNameAndActionName(TerraformAction action) {
            return Optional.of(new ProviderActionDefinition(
                action.providerName(),
                action.providerName(),
                "ecs",
                "resource",
                "instance_count"
            ));
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 测试只验证分析结果。
        }
    }
}
