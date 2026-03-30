package com.sailv.terraform.analysis.domain.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * `t_mp_template_resource` 对应领域对象。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class TemplateQuotaResource {
    private String templateId;
    private String resourceType;
    private String quotaType;
    private int quotaRequirement;
}
