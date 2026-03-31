package com.sailv.terraform.analysis.gateway;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformAction;

import java.util.Collection;
import java.util.List;

/**
 * 模板分析统一网关。
 *
 * <p>同时承担两类职责：
 * <ul>
 *     <li>根据领域动作查询预制表定义</li>
 *     <li>把最终分析结果落入数据库层，并在保存 provider 前再次校验预制表</li>
 * </ul>
 *
 * <p>这样 service 只依赖一个 gateway，更贴合内网项目当前的分层习惯。
 */
public interface TemplateAnalysisGateway {

    /**
     * 批量查询预制表定义。
     */
    List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions);

    void save(TemplateAnalysisResult result);
}
