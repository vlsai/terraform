package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.ProviderType;
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

class TerraformAnalysisServiceCountAggregationTest {

    @Test
    void shouldExpandQuotaResourceRowsByResolvedCount() throws Exception {
        byte[] content = """
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
            .getBytes(StandardCharsets.UTF_8);

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            result = service.analyze(
                "template-counts",
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
        assertEquals("ECS", result.getQuotaResources().getFirst().getResourceType());
        assertEquals("instance", result.getQuotaResources().getFirst().getQuotaType());
        assertEquals(5, result.getQuotaResources().getFirst().getQuotaRequirement());
    }

    private static final class StubTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return actions.stream()
                .map(action -> new ProviderAction(
                    action.getProviderName(),
                    action.getProviderName() + ":permission",
                    "ecs",
                    ProviderType.RESOURCE
                ))
                .distinct()
                .toList();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 测试只验证分析结果。
        }
    }
}
