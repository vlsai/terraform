package com.sailv.terraform.analysis.service;

import com.sailv.terraform.analysis.application.TemplateSource;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;

import java.io.IOException;
import java.util.Collection;

/**
 * Service 层接口。
 *
 * <p>符合你们内网项目的接口 -> service -> gateway 分层风格。
 */
public interface TerraformAnalysisService {

    TemplateAnalysisResult analyze(String templateId, TemplateSource source, Collection<QuotaCheckRule> quotaRules)
        throws IOException;

    void save(TemplateAnalysisResult result);

    default TemplateAnalysisResult analyzeAndSave(
        String templateId,
        TemplateSource source,
        Collection<QuotaCheckRule> quotaRules
    ) throws IOException {
        TemplateAnalysisResult result = analyze(templateId, source, quotaRules);
        save(result);
        return result;
    }
}
