package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.ProviderType;
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

class TerraformAnalysisServiceGenericQuotaHintTest {

    @Test
    void shouldPreferQuotaTypeHintForGenericQuotaSelection() throws Exception {
        byte[] content = """
            resource "huaweicloud_dms_instance" "mq" {
              count = 2
            }
            """
            .getBytes(StandardCharsets.UTF_8);

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new DmsHintGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            result = service.analyze(
                "template-dms-hint",
                inputStream,
                "main.tf",
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("DMS", "https://quota.internal/dms", "rabbitmqInstance", "kafkaInstance", "rocketmqInstance")
                )
            );
        }

        Map<String, Integer> quotaMap = result.getQuotaResources().stream()
            .collect(Collectors.toMap(
                resource -> resource.getResourceType() + ":" + resource.getQuotaType(),
                TemplateQuotaResource::getQuotaRequirement,
                Integer::sum
            ));

        assertEquals(1, quotaMap.size());
        assertEquals(2, quotaMap.get("DMS:kafkaInstance"));
    }

    @Test
    void shouldUseResolvedCountForGenericRdsQuota() throws Exception {
        byte[] content = """
            resource "huaweicloud_rds_instance" "mysql" {
              count = 3
            }
            """
            .getBytes(StandardCharsets.UTF_8);

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new RdsGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            result = service.analyze(
                "template-rds-count",
                inputStream,
                "main.tf",
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("RDS", "https://quota.internal/rds", "instance")
                )
            );
        }

        assertEquals(1, result.getQuotaResources().size());
        assertEquals("RDS", result.getQuotaResources().getFirst().getResourceType());
        assertEquals("instance", result.getQuotaResources().getFirst().getQuotaType());
        assertEquals(3, result.getQuotaResources().getFirst().getQuotaRequirement());
    }

    private static final class DmsHintGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return List.of(new ProviderAction()
                .setProviderName("huaweicloud_dms_instance")
                .setActionName("huaweicloud_dms_instance")
                .setResourceType("DMS")
                .setQuotaTypeHint("kafkaInstance")
                .setProviderType(ProviderType.RESOURCE)
                .setPrimaryQuotaSubject(true));
        }

        @Override
        public void save(TemplateAnalysisResult result) {
        }
    }

    private static final class RdsGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return List.of(new ProviderAction()
                .setProviderName("huaweicloud_rds_instance")
                .setActionName("huaweicloud_rds_instance")
                .setResourceType("RDS")
                .setProviderType(ProviderType.RESOURCE)
                .setPrimaryQuotaSubject(true));
        }

        @Override
        public void save(TemplateAnalysisResult result) {
        }
    }
}
