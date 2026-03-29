package com.sailv.terraform.analysis.gateway;

import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模板分析统一网关。
 *
 * <p>同时承担两类职责：
 * <ul>
 *     <li>根据领域动作查询 provider/action 映射</li>
 *     <li>把最终分析结果落入数据库层，并在保存 provider 前再次校验预制表</li>
 * </ul>
 *
 * <p>这样 service 只依赖一个 gateway，更贴合内网项目当前的分层习惯。
 */
public interface TemplateAnalysisGateway {

    Optional<ProviderActionDefinition> findByProviderNameAndActionName(TerraformAction action);

    void save(TemplateAnalysisResult result);

    default List<ProviderActionDefinition> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
        Map<String, ProviderActionDefinition> resolved = new LinkedHashMap<>();
        if (actions == null) {
            return List.of();
        }
        for (TerraformAction action : actions) {
            if (action == null) {
                continue;
            }
            findByProviderNameAndActionName(action)
                .ifPresent(definition -> resolved.putIfAbsent(keyOf(action.providerName(), action.actionName()), definition));
        }
        return List.copyOf(resolved.values());
    }

    private static String keyOf(String providerName, String actionName) {
        return providerName + '\u0000' + actionName;
    }
}
