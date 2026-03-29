package com.sailv.terraform.analysis.infrastructure.parser;

import com.sailv.terraform.analysis.domain.model.TerraformAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HclTerraformFileParserTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseLocalsAndCountFromTerraformFile() throws Exception {
        Path file = tempDir.resolve("main.tf");
        Files.writeString(file, """
            locals {
              ecs_count = 3
            }

            provider "huaweicloud" {}

            resource "huaweicloud_compute_instance" "web" {
              count = local.ecs_count
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
        TerraformFileParser.ParseResult result = parser.parse(file);

        assertEquals(Set.of("huaweicloud"), result.providerBlockNames());
        assertTrue(result.actions().contains(new TerraformAction(
            "huaweicloud_compute_instance",
            "huaweicloud_compute_instance",
            "web",
            TerraformAction.Kind.RESOURCE,
            3
        )));
        assertTrue(result.actions().contains(new TerraformAction(
            "huaweicloud_eip",
            "huaweicloud_eip",
            "public",
            TerraformAction.Kind.RESOURCE,
            2
        )));
        assertTrue(result.actions().contains(new TerraformAction(
            "huaweicloud_images_image",
            "huaweicloud_images_image",
            "ubuntu",
            TerraformAction.Kind.DATA_SOURCE,
            1
        )));
        assertEquals(
            Set.of("./modules/network"),
            result.moduleReferences().stream().map(TerraformFileParser.ModuleReference::rawSource).collect(java.util.stream.Collectors.toSet())
        );
    }
}
