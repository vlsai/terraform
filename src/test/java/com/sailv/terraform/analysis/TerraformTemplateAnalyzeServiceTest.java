package com.sailv.terraform.analysis;

import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.application.TerraformTemplateAnalyzeService;
import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.port.ProviderActionQueryPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformTemplateAnalyzeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAnalyzeTfAndRecursivelyResolveLocalModules() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("tf-root"));
        Files.writeString(root.resolve("main.tf"), """
            terraform {
              required_providers {
                aws = { source = "hashicorp/aws" }
                local = { source = "hashicorp/local" }
              }
            }

            provider "aws" {}

            resource "aws_instance" "web" {}

            module "network" {
              source = "./modules/network"
            }

            module "remote_network" {
              source = "terraform-aws-modules/vpc/aws"
            }
            """);
        Path networkModule = Files.createDirectories(root.resolve("modules/network"));
        Files.writeString(networkModule.resolve("main.tf"), """
            resource "aws_vpc" "main" {}
            data "aws_ami" "ubuntu" {}
            """);

        TerraformTemplateAnalyzeService service = new TerraformTemplateAnalyzeService(new InMemoryProviderActionQueryPort(Map.of(
            "aws|aws_instance", new ProviderActionDefinition("aws", "aws_instance", "ecs", "aws_cloud", "instance_count"),
            "aws|aws_vpc", new ProviderActionDefinition("aws", "aws_vpc", "vpc", "aws_cloud", null),
            "aws|aws_ami", new ProviderActionDefinition("aws", "aws_ami", "image", "aws_cloud", null)
        )));

        TemplateAnalysisResult result = service.analyze(
            1001L,
            TemplateSource.fromPath(root.resolve("main.tf")),
            List.of(QuotaCheckRule.of("ecs", "https://quota.example/ecs"))
        );

        assertEquals(1, result.providers().size());
        assertEquals("aws", result.providers().getFirst().providerName());
        assertEquals("aws_cloud", result.providers().getFirst().providerType());

        assertEquals(1, result.quotaResources().size());
        assertEquals("ecs", result.quotaResources().getFirst().resourceType());
        assertEquals("instance_count", result.quotaResources().getFirst().quotaType());

        assertEquals(1, result.quotaChecks().size());
        assertEquals("aws_instance", result.quotaChecks().getFirst().actionName());
        assertTrue(result.warnings().stream().anyMatch(warning -> "REMOTE_MODULE_SKIPPED".equals(warning.code())));
    }

    @Test
    void shouldAnalyzeTerraformJsonAndResolveNestedModuleResources() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("json-root"));
        Files.writeString(root.resolve("main.tf.json"), """
            {
              "provider": {
                "alicloud": [
                  { "region": "cn-shanghai" }
                ]
              },
              "resource": {
                "alicloud_vpc": {
                  "main": {}
                }
              },
              "module": {
                "compute": {
                  "source": "./modules/compute"
                }
              }
            }
            """);
        Path computeModule = Files.createDirectories(root.resolve("modules/compute"));
        Files.writeString(computeModule.resolve("main.tf.json"), """
            {
              "resource": {
                "alicloud_instance": {
                  "web": {}
                }
              }
            }
            """);

        TerraformTemplateAnalyzeService service = new TerraformTemplateAnalyzeService(new InMemoryProviderActionQueryPort(Map.of(
            "alicloud|alicloud_vpc", new ProviderActionDefinition("alicloud", "alicloud_vpc", "vpc", "aliyun_cloud", null),
            "alicloud|alicloud_instance", new ProviderActionDefinition("alicloud", "alicloud_instance", "ecs", "aliyun_cloud", "instance_count")
        )));

        TemplateAnalysisResult result = service.analyze(
            1002L,
            TemplateSource.fromPath(root.resolve("main.tf.json")),
            List.of(QuotaCheckRule.of("ecs", "https://quota.example/ecs"))
        );

        assertEquals(1, result.providers().size());
        assertEquals("alicloud", result.providers().getFirst().providerName());
        assertEquals("aliyun_cloud", result.providers().getFirst().providerType());

        assertEquals(1, result.quotaResources().size());
        assertEquals("ecs", result.quotaResources().getFirst().resourceType());
        assertEquals("instance_count", result.quotaResources().getFirst().quotaType());
        assertEquals(1, result.quotaChecks().size());
    }

    @Test
    void shouldAnalyzeZipWithNestedRootDirectory() throws Exception {
        byte[] zipContent = createZip(Map.ofEntries(
            Map.entry("bundle/main.tf", """
                provider "huaweicloud" {}

                module "compute" {
                  source = "./modules/compute"
                }
                """),
            Map.entry("bundle/modules/compute/main.tf", """
                resource "huaweicloud_compute_instance" "vm" {}
                """)
        ));

        TerraformTemplateAnalyzeService service = new TerraformTemplateAnalyzeService(new InMemoryProviderActionQueryPort(Map.of(
            "huaweicloud|huaweicloud_compute_instance",
            new ProviderActionDefinition("huaweicloud", "huaweicloud_compute_instance", "ecs", "huaweicloud", "instance_count")
        )));

        TemplateAnalysisResult result = service.analyze(
            1003L,
            TemplateSource.fromBytes("template.zip", zipContent),
            List.of(QuotaCheckRule.of("ecs", "https://quota.example/ecs"))
        );

        assertEquals(1, result.providers().size());
        assertEquals("huaweicloud", result.providers().getFirst().providerName());
        assertEquals(1, result.quotaResources().size());
        assertEquals("ecs", result.quotaResources().getFirst().resourceType());
        assertTrue(result.warnings().stream().noneMatch(warning -> "NO_TERRAFORM_FILES".equals(warning.code())));
    }

    private static byte[] createZip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zipOutputStream.closeEntry();
            }
        }
        return outputStream.toByteArray();
    }

    private static final class InMemoryProviderActionQueryPort implements ProviderActionQueryPort {
        private final Map<String, ProviderActionDefinition> definitions = new LinkedHashMap<>();

        private InMemoryProviderActionQueryPort(Map<String, ProviderActionDefinition> definitions) {
            this.definitions.putAll(definitions);
        }

        @Override
        public Optional<ProviderActionDefinition> findByProviderNameAndActionName(String providerName, String actionName) {
            return Optional.ofNullable(definitions.get(providerName + "|" + actionName));
        }
    }
}
