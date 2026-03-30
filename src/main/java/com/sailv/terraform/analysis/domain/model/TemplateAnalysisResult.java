package com.sailv.terraform.analysis.domain.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终分析结果。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class TemplateAnalysisResult {
    private String templateId;
    private List<TemplateProvider> providers = new ArrayList<>();
    private List<TemplateQuotaResource> quotaResources = new ArrayList<>();
}
