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
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class ProviderActionDefinition {
    private String providerName;
    private String actionName;
    private String resourceType;
    private String providerType;
}
