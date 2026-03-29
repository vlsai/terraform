package com.sailv.terraform.analysis.domain.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 最终输出给上层系统的分析结果。
 *
 * <p>该对象已经是“便于落库 / 便于后续处理”的形态：
 * providers、quotaResources 都可以直接被应用层消费。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
public final class TemplateAnalysisResult {
    private final String templateId;
    private final List<TemplateProvider> providers;
    private final List<TemplateQuotaResource> quotaResources;

    public TemplateAnalysisResult(
        String templateId,
        List<TemplateProvider> providers,
        List<TemplateQuotaResource> quotaResources
    ) {
        this.templateId = templateId;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.quotaResources = quotaResources == null ? List.of() : List.copyOf(quotaResources);
    }
}
