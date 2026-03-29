package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformAnalysisServiceProviderValidationTest {

    @Test
    void shouldSkipProviderWhenPresetTableDoesNotContainIt() throws Exception {
        TemplateSource source = TemplateSource.fromBytes(
            "main.tf",
            """
                provider "unknowncloud" {}
                """
                .getBytes(StandardCharsets.UTF_8)
        );

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new EmptyTemplateAnalysisGateway());
        TemplateAnalysisResult result = service.analyze("template-provider-validation", source, List.of());

        assertTrue(result.providers().isEmpty());
    }

    private static final class EmptyTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public Optional<ProviderActionDefinition> findByProviderNameAndActionName(TerraformAction action) {
            return Optional.empty();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
            // 测试只验证分析结果。
        }
    }
}
