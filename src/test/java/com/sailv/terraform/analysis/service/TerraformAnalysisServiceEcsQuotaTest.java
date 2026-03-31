package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateQuotaResource;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerraformAnalysisServiceEcsQuotaTest {

    @Test
    void shouldCalculateEcsAndSystemDiskQuotas() throws Exception {
        byte[] content = """
            locals {
              ecs_count = 2
              ecs_flavor = "s6.2xlarge.4"
              system_disk = 40
            }

            resource "huaweicloud_compute_instance" "ecs" {
              count = local.ecs_count
              flavor_id = local.ecs_flavor
              system_disk_size = local.system_disk
            }
            """
            .getBytes(StandardCharsets.UTF_8);

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            result = service.analyze(
                "template-ecs-quota",
                inputStream,
                "main.tf",
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("ECS", "https://quota.internal/ecs", "instance", "cpu_count", "ram_count"),
                    QuotaCheckRule.CloudServiceRule.of("EVS", "https://quota.internal/evs", "volumes", "gigabytes")
                )
            );
        }

        Map<String, Integer> quotaMap = result.getQuotaResources().stream()
            .collect(Collectors.toMap(
                resource -> resource.getResourceType() + ":" + resource.getQuotaType(),
                TemplateQuotaResource::getQuotaRequirement,
                Integer::sum
            ));

        assertEquals(2, quotaMap.get("ECS:instance"));
        assertEquals(16, quotaMap.get("ECS:cpu_count"));
        assertEquals(64, quotaMap.get("ECS:ram_count"));
        assertEquals(2, quotaMap.get("EVS:volumes"));
        assertEquals(80, quotaMap.get("EVS:gigabytes"));
    }

    private static final class StubTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return actions.stream()
                .map(TerraformAction::getProviderName)
                .distinct()
                .map(providerName -> new ProviderAction()
                    .setProviderName(providerName)
                    .setActionName(providerName + ":permission")
                    .setResourceType("ecs")
                    .setProviderType(ProviderAction.ProviderType.RESOURCE))
                .toList();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
        }
    }
}
