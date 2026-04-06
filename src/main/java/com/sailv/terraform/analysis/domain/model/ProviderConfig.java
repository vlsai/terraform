package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * provider 配额配置领域映射。
 *
 * <p>当前由 `t_mp_provider_config` 映射得到。
 */
@Getter
@Setter
@Accessors(chain = true)
public class ProviderConfig {
    private String providerName;
    private String resourceType;
    private String quotaTypeHint;
    private ProviderType providerType;
    private boolean primaryQuotaSubject = true;

    public ProviderConfig() {
    }

    public ProviderConfig(String providerName, String resourceType, ProviderType providerType) {
        this(providerName, resourceType, null, providerType, true);
    }

    public ProviderConfig(String providerName, String resourceType, ProviderType providerType, boolean primaryQuotaSubject) {
        this(providerName, resourceType, null, providerType, primaryQuotaSubject);
    }

    public ProviderConfig(
        String providerName,
        String resourceType,
        String quotaTypeHint,
        ProviderType providerType,
        boolean primaryQuotaSubject
    ) {
        this.providerName = providerName;
        this.resourceType = resourceType;
        this.quotaTypeHint = quotaTypeHint;
        this.providerType = providerType;
        this.primaryQuotaSubject = primaryQuotaSubject;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ProviderConfig other)) {
            return false;
        }
        return primaryQuotaSubject == other.primaryQuotaSubject
            && java.util.Objects.equals(providerName, other.providerName)
            && java.util.Objects.equals(resourceType, other.resourceType)
            && java.util.Objects.equals(quotaTypeHint, other.quotaTypeHint)
            && providerType == other.providerType;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(providerName, resourceType, quotaTypeHint, providerType, primaryQuotaSubject);
    }
}
