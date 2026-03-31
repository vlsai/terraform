package com.sailv.terraform.analysis.domain.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Locale;

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

    /**
     * 预制表 `provider_type` 列的枚举映射。
     *
     * <p>数据库中只存 `"resource"` / `"data"` 两种字符串值。
     */
    public enum ProviderType {
        RESOURCE("resource"),
        DATA("data");

        @Getter
        private final String dbValue;

        ProviderType(String dbValue) {
            this.dbValue = dbValue;
        }

        /**
         * 从数据库字符串值转换为枚举。
         *
         * <p>支持 "resource" / "data" / "datasource" 等常见写法。
         */
        public static ProviderType fromDbValue(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "resource" -> RESOURCE;
                case "data", "datasource", "data_source" -> DATA;
                default -> null;
            };
        }
    }
}
