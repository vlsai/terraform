package com.sailv.terraform.analysis.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配额检查规则。
 *
 * <p>内网项目里这份配置通常来自一段 JSON，结构是：
 *
 * <pre>{@code
 * {
 *   "cloud_service": [
 *     {
 *       "resource_type": "ECS",
 *       "endpoint": "",
 *       "quota_type": ["instance", "cpu_count", "ram_count"]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>这里保留原始配置结构，领域层再基于 `resource_type` 建索引。
 * 最终落库时也优先使用规则里配置的原始 `resource_type` / `quota_type`，
 * 这样可以保持和内网配额系统配置一致的命名。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Accessors(chain = true)
public class QuotaCheckRule {

    @JsonProperty("cloud_service")
    private List<CloudServiceRule> cloudService = new ArrayList<>();

    public static QuotaCheckRule of(CloudServiceRule... services) {
        QuotaCheckRule rule = new QuotaCheckRule();
        if (services != null) {
            for (CloudServiceRule service : services) {
                if (service != null) {
                    rule.getCloudService().add(service);
                }
            }
        }
        return rule;
    }

    public Map<String, CloudServiceRule> indexByResourceType() {
        Map<String, CloudServiceRule> indexed = new LinkedHashMap<>();
        if (cloudService == null) {
            return indexed;
        }
        for (CloudServiceRule rule : cloudService) {
            if (rule == null || rule.getResourceType() == null || rule.getResourceType().isBlank()) {
                continue;
            }
            indexed.put(normalizeResourceType(rule.getResourceType()), rule);
        }
        return indexed;
    }

    public static String normalizeResourceType(String resourceType) {
        if (resourceType == null) {
            return null;
        }
        String normalized = resourceType.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.replace('-', '_').toLowerCase(java.util.Locale.ROOT);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    @EqualsAndHashCode
    @Accessors(chain = true)
    public static class CloudServiceRule {

        @JsonProperty("resource_type")
        private String resourceType;

        private String endpoint;

        @JsonProperty("quota_type")
        private List<String> quotaType = new ArrayList<>();

        public static CloudServiceRule of(String resourceType, String endpoint, String... quotaTypes) {
            CloudServiceRule rule = new CloudServiceRule()
                .setResourceType(resourceType)
                .setEndpoint(endpoint);
            if (quotaTypes != null) {
                for (String quotaType : quotaTypes) {
                    if (quotaType != null && !quotaType.isBlank()) {
                        rule.getQuotaType().add(quotaType);
                    }
                }
            }
            return rule;
        }
    }
}
