package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
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

    private static final class EmptyTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderActionDefinition> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return List.of();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 测试只验证分析结果。
        }
    }
}
