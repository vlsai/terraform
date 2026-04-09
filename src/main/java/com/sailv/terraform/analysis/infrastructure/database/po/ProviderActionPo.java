package com.sailv.terraform.analysis.infrastructure.database.po;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * `t_mp_provider_actions` 数据库层对象。
 */
@Getter
@Setter
@Accessors(chain = true)
public class ProviderActionPo {
    private String providerName;
    private String actionName;
    private String resourceType;
    private String quotaTypeHint;
    private String providerType;
    private Integer isPrimaryQuotaSubject;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public ProviderActionPo() {
    }
}
