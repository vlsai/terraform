package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * provider 使用键。
 *
 * <p>同一个 Terraform 类型可能同时存在 resource 和 data 两类定义，
 * 因此预制表和模板分析内部都必须按 `(providerName, providerType)` 区分。
 */
@Getter
@Setter
@Accessors(chain = true)
public class ProviderUsageKey {
    private String providerName;
    private ProviderType providerType;

    public ProviderUsageKey() {
    }

    public ProviderUsageKey(String providerName, ProviderType providerType) {
        this.providerName = providerName;
        this.providerType = providerType;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ProviderUsageKey other)) {
            return false;
        }
        return java.util.Objects.equals(providerName, other.providerName) && providerType == other.providerType;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(providerName, providerType);
    }
}
