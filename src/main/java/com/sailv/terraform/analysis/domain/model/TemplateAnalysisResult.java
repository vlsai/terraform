package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终分析结果。
 */
@Getter
@Setter
@Accessors(chain = true)
public class TemplateAnalysisResult {
    private String templateId;
    private List<TemplateProvider> providers = new ArrayList<>();
    private List<TemplateQuotaResource> quotaResources = new ArrayList<>();

    public TemplateAnalysisResult() {
    }

    public TemplateAnalysisResult(String templateId, List<TemplateProvider> providers, List<TemplateQuotaResource> quotaResources) {
        this.templateId = templateId;
        this.providers = providers;
        this.quotaResources = quotaResources;
    }
}
