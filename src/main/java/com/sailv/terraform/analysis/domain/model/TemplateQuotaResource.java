package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * `t_mp_template_resource` 对应领域对象。
 */
@Getter
@Setter
@Accessors(chain = true)
public class TemplateQuotaResource {
    private String templateId;
    private String resourceType;
    private String quotaType;
    private int quotaRequirement;

    public TemplateQuotaResource() {
    }

    public TemplateQuotaResource(String templateId, String resourceType, String quotaType, int quotaRequirement) {
        this.templateId = templateId;
        this.resourceType = resourceType;
        this.quotaType = quotaType;
        this.quotaRequirement = quotaRequirement;
    }
}
