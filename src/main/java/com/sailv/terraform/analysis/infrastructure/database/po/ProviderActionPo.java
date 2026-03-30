package com.sailv.terraform.analysis.infrastructure.database.po;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * `t_mp_provider_actions` 数据库层对象。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class ProviderActionPo {
    private String providerName;
    private String actionName;
    private String resourceType;
    private String providerType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
