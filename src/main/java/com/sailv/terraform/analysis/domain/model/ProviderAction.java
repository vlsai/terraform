package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * provider action 预制定义的领域映射。
 *
 * <p>当前由 `t_mp_provider_actions` 映射得到。
 */
@Getter
@Setter
@Accessors(chain = true)
public class ProviderAction {
    private String providerName;
    private String actionName;
    private String resourceType;
    private ProviderType providerType;

    public ProviderAction() {
    }

    public ProviderAction(String providerName, String actionName, String resourceType, ProviderType providerType) {
        this.providerName = providerName;
        this.actionName = actionName;
        this.resourceType = resourceType;
        this.providerType = providerType;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ProviderAction other)) {
            return false;
        }
        return java.util.Objects.equals(providerName, other.providerName)
            && java.util.Objects.equals(actionName, other.actionName)
            && java.util.Objects.equals(resourceType, other.resourceType)
            && providerType == other.providerType;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(providerName, actionName, resourceType, providerType);
    }
}
