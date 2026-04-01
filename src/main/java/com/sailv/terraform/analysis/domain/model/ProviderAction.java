package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 预制表 `t_mp_provider_actions` 的领域映射。
 *
 * <p>同一个 providerName 可能同时存在 RESOURCE 和 DATA 两种类型的记录。
 */
@Getter
@Setter
@Accessors(chain = true)
public class ProviderAction {
    private String providerName;
    private String actionName;
    private String resourceType;
    private String quotaTypeHint;
    private ProviderType providerType;
    private boolean primaryQuotaSubject = true;

    public ProviderAction() {
    }

    public ProviderAction(String providerName, String actionName, String resourceType, ProviderType providerType) {
        this(providerName, actionName, resourceType, null, providerType, true);
    }

    public ProviderAction(String providerName, String actionName, String resourceType, ProviderType providerType, boolean primaryQuotaSubject) {
        this(providerName, actionName, resourceType, null, providerType, primaryQuotaSubject);
    }

    public ProviderAction(
        String providerName,
        String actionName,
        String resourceType,
        String quotaTypeHint,
        ProviderType providerType,
        boolean primaryQuotaSubject
    ) {
        this.providerName = providerName;
        this.actionName = actionName;
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
        if (!(object instanceof ProviderAction other)) {
            return false;
        }
        return primaryQuotaSubject == other.primaryQuotaSubject
                && java.util.Objects.equals(providerName, other.providerName)
                && java.util.Objects.equals(actionName, other.actionName)
                && java.util.Objects.equals(resourceType, other.resourceType)
                && java.util.Objects.equals(quotaTypeHint, other.quotaTypeHint)
                && providerType == other.providerType;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(providerName, actionName, resourceType, quotaTypeHint, providerType, primaryQuotaSubject);
    }
}
