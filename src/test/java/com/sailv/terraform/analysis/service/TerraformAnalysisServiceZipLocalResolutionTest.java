package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.impl.TerraformAnalysisServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerraformAnalysisServiceZipLocalResolutionTest {

    @Test
    void shouldResolveLocalCountAcrossFilesInSameZipDirectory() throws Exception {
        byte[] zipContent = buildZip(
            "code/modules/ecs/locals.tf", """
                locals {
                  ecs_count = 2
                }
                """,
            "code/modules/ecs/main.tf", """
                resource "huaweicloud_compute_instance" "web" {
                  count = local.ecs_count
                }
                """
        );

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(zipContent)) {
            result = service.analyze(
                "template-zip-local",
                inputStream,
                "template.zip",
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("ECS", "https://quota.internal/ecs", "instance")
                )
            );
        }

        assertEquals(1, result.getProviders().size());
        assertEquals("huaweicloud_compute_instance", result.getProviders().getFirst().getProviderName());
        assertEquals(1, result.getQuotaResources().size());
        assertEquals("ECS", result.getQuotaResources().getFirst().getResourceType());
        assertEquals(2, result.getQuotaResources().getFirst().getQuotaRequirement());
    }

    @Test
    void shouldNotOverwriteSameBlockNameAcrossDifferentZipDirectories() throws Exception {
        byte[] zipContent = buildZip(
            "code/modules/ecs-a/main.tf", """
                resource "huaweicloud_compute_instance" "web" {
                  count = 1
                }
                """,
            "code/modules/ecs-b/main.tf", """
                resource "huaweicloud_compute_instance" "web" {
                  count = 2
                }
                """
        );

        TerraformAnalysisService service = new TerraformAnalysisServiceImpl(new StubTemplateAnalysisGateway());
        TemplateAnalysisResult result;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(zipContent)) {
            result = service.analyze(
                "template-zip-duplicate-block",
                inputStream,
                "template.zip",
                QuotaCheckRule.of(
                    QuotaCheckRule.CloudServiceRule.of("ECS", "https://quota.internal/ecs", "instance")
                )
            );
        }

        assertEquals(1, result.getQuotaResources().size());
        assertEquals(3, result.getQuotaResources().getFirst().getQuotaRequirement());
    }

    private byte[] buildZip(String firstPath, String firstContent, String secondPath, String secondContent) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry(firstPath));
            zipOutputStream.write(firstContent.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();

            zipOutputStream.putNextEntry(new ZipEntry(secondPath));
            zipOutputStream.write(secondContent.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return outputStream.toByteArray();
    }

    private static final class StubTemplateAnalysisGateway implements TemplateAnalysisGateway {

        @Override
        public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
            return actions.stream()
                .map(action -> new ProviderAction(
                    action.getProviderName(),
                    action.getProviderName() + ":permission",
                    "ecs",
                    ProviderAction.ProviderType.RESOURCE
                ))
                .distinct()
                .toList();
        }

        @Override
        public void save(TemplateAnalysisResult result) {
        }
    }
}
