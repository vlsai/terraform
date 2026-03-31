package com.sailv.terraform.analysis.infrastructure.parser;

import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HclTerraformFileParserTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseLocalsAndCountExpressionFromTerraformFile() throws Exception {
        Path file = tempDir.resolve("main.tf");
        Files.writeString(file, """
            locals {
              ecs_count = 3
              ecs_flavor = "s6.2xlarge.4"
              system_disk = 40
            }

            provider "huaweicloud" {}

            resource "huaweicloud_compute_instance" "web" {
              count = local.ecs_count
              flavor_id = local.ecs_flavor
              system_disk_size = local.system_disk
            }

            resource "huaweicloud_eip" "public" {
              count = 2
            }

            data "huaweicloud_images_image" "ubuntu" {}

            module "network" {
              source = "./modules/network"
            }
            """);

        HclTerraformFileParser parser = new HclTerraformFileParser();
        TerraformFileParser.ParseResult result;
        try (InputStream inputStream = Files.newInputStream(file)) {
            result = parser.parse(inputStream, file.getFileName().toString());
        }

        assertEquals(Set.of("huaweicloud"), result.getProviderBlockNames());
        assertEquals("3", result.getLocalValues().get("ecs_count"));
        assertEquals("s6.2xlarge.4", result.getLocalValues().get("ecs_flavor"));

        TerraformAction computeAction = result.getActions().stream()
            .filter(action -> Objects.equals("huaweicloud_compute_instance", action.getProviderName()))
            .filter(action -> Objects.equals("web", action.getBlockName()))
            .findFirst()
            .orElseThrow();
        assertEquals(ProviderType.RESOURCE, computeAction.getProviderType());
        assertTrue(Set.of("local.ecs_count", "3").contains(computeAction.getRequestedAmountExpression()));
        assertTrue(Set.of("local.ecs_flavor", "s6.2xlarge.4").contains(computeAction.getFlavorIdExpression()));
        assertTrue(Set.of("local.system_disk", "40").contains(computeAction.getSystemDiskSizeExpression()));

        TerraformAction eipAction = result.getActions().stream()
            .filter(action -> Objects.equals("huaweicloud_eip", action.getProviderName()))
            .filter(action -> Objects.equals("public", action.getBlockName()))
            .findFirst()
            .orElseThrow();
        assertEquals("2", eipAction.getRequestedAmountExpression());

        TerraformAction dataAction = result.getActions().stream()
            .filter(action -> Objects.equals("huaweicloud_images_image", action.getProviderName()))
            .filter(action -> Objects.equals("ubuntu", action.getBlockName()))
            .findFirst()
            .orElseThrow();
        assertEquals(ProviderType.DATA, dataAction.getProviderType());
        assertEquals(1, dataAction.getRequestedAmount());
    }

    @Test
    void shouldParseLocalsFromLocalsOnlyFile() throws Exception {
        Path file = tempDir.resolve("locals.tf");
        Files.writeString(file, """
            locals {
              ecs_count = 2
            }
            """);

        HclTerraformFileParser parser = new HclTerraformFileParser();
        TerraformFileParser.ParseResult result;
        try (InputStream inputStream = Files.newInputStream(file)) {
            result = parser.parse(inputStream, file.getFileName().toString());
        }

        assertEquals("2", result.getLocalValues().get("ecs_count"));
    }
}
