package com.sailv.terraform.analysis.domain.model;

import java.util.List;

public record TemplateAnalysisResult(
    Long templateId,
    List<TemplateProvider> providers,
    List<TemplateQuotaResource> quotaResources,
    List<QuotaCheckRequest> quotaChecks,
    List<AnalysisWarning> warnings
) {

    public TemplateAnalysisResult {
        providers = providers == null ? List.of() : List.copyOf(providers);
        quotaResources = quotaResources == null ? List.of() : List.copyOf(quotaResources);
        quotaChecks = quotaChecks == null ? List.of() : List.copyOf(quotaChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
