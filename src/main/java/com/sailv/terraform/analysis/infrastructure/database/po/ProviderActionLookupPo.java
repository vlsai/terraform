package com.sailv.terraform.analysis.infrastructure.database.po;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * provider action 批量查询条件对象。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class ProviderActionLookupPo {
    private String providerName;
    private String actionName;
}
