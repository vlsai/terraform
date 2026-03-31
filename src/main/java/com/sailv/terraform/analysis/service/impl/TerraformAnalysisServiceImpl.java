package com.sailv.terraform.analysis.service.impl;

import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformTemplate;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.infrastructure.parser.HclTerraformFileParser;
import com.sailv.terraform.analysis.infrastructure.parser.JsonTerraformFileParser;
import com.sailv.terraform.analysis.service.TerraformAnalysisService;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service 层实现。
 *
 * <p>责任已极大简化为纯粹的流程编排：
 * <ul>
 *     <li>构造 `TerraformTemplate` 并在构造阶段完成源码解析和数据合并收集</li>
 *     <li>通过 Gateway 获取数据库预设映射（t_mp_provider_actions）</li>
 *     <li>委托 `TerraformTemplate.extractQuotas` 基于提取的数据和预制表生成具体的用量配额和 Provider 供存储</li>
 * </ul>
 */
public class TerraformAnalysisServiceImpl implements TerraformAnalysisService {

    private final List<TerraformFileParser> parsers;
    private final TemplateAnalysisGateway templateAnalysisGateway;

    public TerraformAnalysisServiceImpl(TemplateAnalysisGateway templateAnalysisGateway) {
        this(List.of(new JsonTerraformFileParser(), new HclTerraformFileParser()), templateAnalysisGateway);
    }

    public TerraformAnalysisServiceImpl(
        List<TerraformFileParser> parsers,
        TemplateAnalysisGateway templateAnalysisGateway
    ) {
        this.parsers = List.copyOf(parsers);
        this.templateAnalysisGateway = Objects.requireNonNull(templateAnalysisGateway, "templateAnalysisGateway cannot be null");
    }

    @Override
    public TemplateAnalysisResult analyze(
        String templateId,
        InputStream inputStream,
        String fileName,
        QuotaCheckRule quotaRules
    ) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream cannot be null");
        Objects.requireNonNull(fileName, "fileName cannot be null");

        // 1. 构造领域对象时立即完成模板解析和本地变量推演
        TerraformTemplate template = new TerraformTemplate(templateId, inputStream, fileName, parsers);

        // 2. 通过聚合的动作从底层存储拿到真实的映射和种类描述
        List<ProviderAction> presetActions = templateAnalysisGateway.findByProviderNameAndActionName(
            template.getActions().values()
        );

        // 3. 在进入领域计算前先按 providerName 归组，领域层只消费已经结构化的数据
        return template.extractQuotas(quotaRules, indexProviderActions(presetActions));
    }

    @Override
    public void save(TemplateAnalysisResult result) {
        templateAnalysisGateway.save(result);
    }

    private Map<String, List<ProviderAction>> indexProviderActions(List<ProviderAction> providerActions) {
        Map<String, List<ProviderAction>> indexed = new LinkedHashMap<>();
        if (providerActions == null) {
            return indexed;
        }
        for (ProviderAction providerAction : providerActions) {
            if (providerAction == null || providerAction.getProviderName() == null || providerAction.getProviderName().isBlank()) {
                continue;
            }
            indexed.computeIfAbsent(providerAction.getProviderName(), ignored -> new java.util.ArrayList<>()).add(providerAction);
        }
        return indexed;
    }
}
