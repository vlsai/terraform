package com.sailv.terraform.analysis.infrastructure.database.po;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * provider 配额配置数据库层对象。
 *
 * <p>查询时由 `t_mp_provider_config` 映射得到。
 */
@Getter
@Setter
@Accessors(chain = true)
public class ProviderConfigPo {
    private String id;
    private String providerName;
    private String providerType;
    private Integer isPrimaryQuotaSubject;
    private String quotaResourceType;
    private String quotaTypeHint;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public ProviderConfigPo() {
    }
}
