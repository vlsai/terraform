package com.sailv.terraform.analysis.domain.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 预制表 `t_mp_provider_actions` 的领域映射。
 *
 * <p>同一个 providerName 可能同时存在 RESOURCE 和 DATA 两种类型的记录。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class ProviderAction {
    private String providerName;
    private String actionName;
    private String resourceType;
    private ProviderType providerType;
}
