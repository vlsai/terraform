package com.sailv.terraform.analysis.infrastructure.gateway;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.infrastructure.database.convertor.TemplateAnalysisDataConvertor;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderActionMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;
import lombok.extern.log4j.Log4j2;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于数据库 mapper 的统一网关实现。
 *
 * <p>同时负责：
 * <ul>
 *     <li>查询 `t_mp_provider_actions`</li>
 *     <li>落库 `t_mp_template_providers` / `t_mp_template_resource`</li>
 * </ul>
 *
 * <p>这里保持最直接的实现：
 * <ul>
 *     <li>查询时只做一次批量 providerName 查询</li>
 *     <li>保存时直接批量插入两张结果表</li>
 * </ul>
 */
@Log4j2
public class DatabaseTemplateAnalysisGateway implements TemplateAnalysisGateway {

    private final ProviderActionMapper providerActionMapper;
    private final TemplateProviderMapper templateProviderMapper;
    private final TemplateQuotaResourceMapper templateQuotaResourceMapper;
    private final TemplateAnalysisDataConvertor convertor;

    public DatabaseTemplateAnalysisGateway(ProviderActionMapper providerActionMapper, TemplateProviderMapper templateProviderMapper, TemplateQuotaResourceMapper templateQuotaResourceMapper) {
        this(providerActionMapper, templateProviderMapper, templateQuotaResourceMapper, TemplateAnalysisDataConvertor.INSTANCE);
    }

    public DatabaseTemplateAnalysisGateway(ProviderActionMapper providerActionMapper, TemplateProviderMapper templateProviderMapper, TemplateQuotaResourceMapper templateQuotaResourceMapper, TemplateAnalysisDataConvertor convertor) {
        this.providerActionMapper = Objects.requireNonNull(providerActionMapper, "providerActionMapper cannot be null");
        this.templateProviderMapper = Objects.requireNonNull(templateProviderMapper, "templateProviderMapper cannot be null");
        this.templateQuotaResourceMapper = Objects.requireNonNull(templateQuotaResourceMapper, "templateQuotaResourceMapper cannot be null");
        this.convertor = Objects.requireNonNull(convertor, "convertor cannot be null");
    }

    @Override
    public List<ProviderAction> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        Set<String> providerNames = actions.stream()
            .filter(Objects::nonNull)
            .map(TerraformAction::getProviderName)
            .filter(providerName -> providerName != null && !providerName.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (providerNames.isEmpty()) {
            return List.of();
        }

        List<ProviderActionPo> fetched = providerActionMapper.selectByProviderNames(providerNames);
        return convertor.toProviderActions(fetched == null ? List.of() : fetched);
    }

    @Override
    public void save(TemplateAnalysisResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        List<TemplateProvider> validatedProviders = validateProvidersBeforeInsert(result.getProviders());
        if (!validatedProviders.isEmpty()) {
            templateProviderMapper.insertBatch(convertor.toTemplateProviderPos(validatedProviders));
        }
        if (result.getQuotaResources() != null && !result.getQuotaResources().isEmpty()) {
            templateQuotaResourceMapper.insertBatch(convertor.toTemplateQuotaResourcePos(result.getQuotaResources()));
        }
    }

    private List<TemplateProvider> validateProvidersBeforeInsert(
        Collection<TemplateProvider> providers
    ) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }

        List<TemplateProvider> orderedProviders = providers.stream()
            .filter(Objects::nonNull)
            .toList();
        if (orderedProviders.isEmpty()) {
            return List.of();
        }

        Set<String> requestedProviderNames = orderedProviders.stream()
            .map(TemplateProvider::getProviderName)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedProviderNames.isEmpty()) {
            return List.of();
        }

        List<String> existingProviderNames = providerActionMapper.selectExistingProviderNames(requestedProviderNames);
        Set<String> validProviderNames = new LinkedHashSet<>(existingProviderNames == null ? List.of() : existingProviderNames);

        orderedProviders.stream()
            .map(TemplateProvider::getProviderName)
            .filter(Objects::nonNull)
            .filter(providerName -> !validProviderNames.contains(providerName))
            .distinct()
            .forEach(providerName -> log.warn(
                "Skip provider insert because preset table does not contain the provider. providerName={}",
                providerName
            ));

        return orderedProviders.stream()
            .filter(provider -> validProviderNames.contains(provider.getProviderName()))
            .toList();
    }
}
