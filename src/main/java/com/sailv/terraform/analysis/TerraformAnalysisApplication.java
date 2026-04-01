package com.sailv.terraform.analysis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.sailv.terraform.analysis.infrastructure.database.mapper")
public class TerraformAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(TerraformAnalysisApplication.class, args);
    }
}
