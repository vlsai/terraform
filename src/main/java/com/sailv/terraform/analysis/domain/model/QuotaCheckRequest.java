package com.sailv.terraform.analysis.domain.model;

/**
 * 配额检查请求框架。
 *
 * <p>当前库只生成这个对象，不真的调用 URL。
 * 这样调用方可以在内网环境里按自己的认证、网关、日志规范去执行检查。
 */
public record QuotaCheckRequest(
    String templateId,
    String providerName,
    String actionName,
    String resourceType,
    String quotaType,
    String checkUrl
) {
}
