package com.sailv.terraform.analysis.infrastructure.parser;

import com.sailv.terraform.analysis.application.model.ParsedTerraformFile;
import java.io.IOException;
import java.nio.file.Path;

public interface TerraformFileParser {

    boolean supports(Path file);

    ParsedTerraformFile parse(Path file) throws IOException;
}
