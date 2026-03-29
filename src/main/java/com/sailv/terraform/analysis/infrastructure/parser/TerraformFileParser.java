package com.sailv.terraform.analysis.infrastructure.parser;

import com.sailv.terraform.analysis.application.model.ParsedTerraformFile;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Terraform 文件解析器 SPI。
 *
 * <p>内网迁移时如果要换成真正的 `hcl2j`、Jackson 或其他组件，
 * 只需要保持这个接口不变即可。
 */
public interface TerraformFileParser {

    boolean supports(Path file);

    ParsedTerraformFile parse(Path file) throws IOException;
}
