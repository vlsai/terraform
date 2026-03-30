package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;

import java.io.IOException;
import java.io.InputStream;

/**
 * Service 层接口。
 */
public interface TerraformAnalysisService {

    TemplateAnalysisResult analyze(
        String templateId,
        InputStream inputStream,
        String fileName,
        QuotaCheckRule quotaRules
    ) throws IOException;

    void save(TemplateAnalysisResult result);

    default TemplateAnalysisResult analyzeAndSave(
        String templateId,
        InputStream inputStream,
        String fileName,
        QuotaCheckRule quotaRules
    ) throws IOException {
        TemplateAnalysisResult result = analyze(templateId, inputStream, fileName, quotaRules);
        save(result);
        return result;
    }
}
