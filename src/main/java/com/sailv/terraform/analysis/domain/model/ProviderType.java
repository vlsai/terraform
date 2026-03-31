package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;

import java.util.Locale;

/**
 * provider 类型统一枚举。
 *
 * <p>领域和数据库都只关心两种类型：
 * <ul>
 *     <li>resource</li>
 *     <li>data</li>
 * </ul>
 */
public enum ProviderType {
    RESOURCE("resource"),
    DATA("data");

    @Getter
    private final String dbValue;

    ProviderType(String dbValue) {
        this.dbValue = dbValue;
    }

    public static ProviderType fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "resource" -> RESOURCE;
            case "data", "datasource", "data_source" -> DATA;
            default -> null;
        };
    }
}
