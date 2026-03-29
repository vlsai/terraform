package com.sailv.terraform.analysis.domain.model;

import java.util.List;

/**
 * 最终输出给上层系统的分析结果。
 *
 * <p>该对象已经是“便于落库 / 便于后续处理”的形态：
 * providers、quotaResources、quotaChecks 都可以直接被应用层消费。
 */
public record TemplateAnalysisResult(
    String templateId,
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
