package com.sailv.terraform.analysis.infrastructure.parser;

import com.sailv.terraform.analysis.domain.model.TerraformAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTerraformFileParserTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseProductionLikeTerraformJson() throws Exception {
        Path file = tempDir.resolve("production-sample.tf.json");
        Files.writeString(file, """
            {
              "locals": {
                "ecs_count": 3
              },
              // Provider blocks may be emitted as object or array.
              "provider": {
                "huaweicloud": [
                  {
                    "region": "cn-north-4"
                  }
                ]
              },
              "resource": [
                {
                  "huaweicloud_compute_instance": {
                    "web": {
                      "count": "${local.ecs_count}",
                      "name": "demo"
                    }
                  }
                },
                {
                  "huaweicloud_eip": {
                    "public": {
                      "count": 2,
                      "bandwidth": {
                        "size": 5
                      }
                    }
                  }
                }
              ],
              "data": {
                "huaweicloud_images_image": {
                  "ubuntu": {
                    "name": "Ubuntu 22.04"
                  }
                }
              },
              "module": {
                "network": [
                  {
                    "source": "./modules/network"
                  }
                ],
                "remote": {
                  "source": "git::https://example.com/modules/remote.git"
                }
              },
            }
            """);

        JsonTerraformFileParser parser = new JsonTerraformFileParser();
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
            Set.of("./modules/network", "git::https://example.com/modules/remote.git"),
            result.moduleReferences().stream().map(TerraformFileParser.ModuleReference::rawSource).collect(java.util.stream.Collectors.toSet())
        );
    }
}
