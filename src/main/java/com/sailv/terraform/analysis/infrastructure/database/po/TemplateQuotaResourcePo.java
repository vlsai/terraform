package com.sailv.terraform.analysis.infrastructure.database.po;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * `t_mp_template_resource` 数据库层对象。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class TemplateQuotaResourcePo {
    private String id;
    private String templateId;
    private String resourceType;
    private String quotaType;
    private Integer quotaRequirement;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
