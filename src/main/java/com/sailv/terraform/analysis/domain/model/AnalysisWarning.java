package com.sailv.terraform.analysis.domain.model;

import java.util.Objects;

public record AnalysisWarning(String code, String message, String source) {

    public AnalysisWarning {
        code = normalize(code, "UNKNOWN");
        message = normalize(message, "Unknown warning");
        source = source == null ? "" : source.trim();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
