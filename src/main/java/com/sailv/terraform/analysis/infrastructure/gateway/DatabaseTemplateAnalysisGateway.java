package com.sailv.terraform.analysis.infrastructure.gateway;

import com.sailv.terraform.analysis.domain.model.ProviderConfig;
import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.ProviderUsageKey;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.infrastructure.database.convertor.TemplateAnalysisDataConvertor;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderConfigMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderConfigPo;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 *     <li>查询 `t_mp_provider_config` 预制定义</li>
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
@Component
public class DatabaseTemplateAnalysisGateway implements TemplateAnalysisGateway {

    private final ProviderConfigMapper providerConfigMapper;
    private final TemplateProviderMapper templateProviderMapper;
    private final TemplateQuotaResourceMapper templateQuotaResourceMapper;
    private final TemplateAnalysisDataConvertor convertor;

    @Autowired
    public DatabaseTemplateAnalysisGateway(
        ProviderConfigMapper providerConfigMapper,
        TemplateProviderMapper templateProviderMapper,
        TemplateQuotaResourceMapper templateQuotaResourceMapper
    ) {
        this.providerConfigMapper = Objects.requireNonNull(providerConfigMapper, "providerConfigMapper cannot be null");
        this.templateProviderMapper = Objects.requireNonNull(templateProviderMapper, "templateProviderMapper cannot be null");
        this.templateQuotaResourceMapper = Objects.requireNonNull(templateQuotaResourceMapper, "templateQuotaResourceMapper cannot be null");
        this.convertor = TemplateAnalysisDataConvertor.INSTANCE;
    }

    @Override
    public List<ProviderConfig> findByProviderUsages(Collection<TerraformAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        Set<ProviderUsageKey> providerUsages = actions.stream()
            .filter(Objects::nonNull)
            .filter(action -> action.getProviderName() != null && !action.getProviderName().isBlank())
            .filter(action -> action.getProviderType() != null)
            .map(action -> new ProviderUsageKey(action.getProviderName(), action.getProviderType()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (providerUsages.isEmpty()) {
            return List.of();
        }

        List<ProviderConfigPo> fetched = providerConfigMapper.selectByProviderUsages(providerUsages);
        return convertor.toProviderConfigs(fetched == null ? List.of() : fetched);
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

        Set<ProviderUsageKey> requestedProviderUsages = orderedProviders.stream()
            .filter(provider -> provider.getProviderName() != null && !provider.getProviderName().isBlank())
            .map(convertor::toProviderUsageKey)
            .filter(Objects::nonNull)
            .filter(usage -> usage.getProviderName() != null && !usage.getProviderName().isBlank())
            .filter(usage -> usage.getProviderType() != null)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedProviderUsages.isEmpty()) {
            return List.of();
        }

        List<ProviderConfigPo> existingProviderUsageRows = providerConfigMapper.selectExistingProviderUsages(requestedProviderUsages);
        Set<ProviderUsageKey> validProviderUsages = (existingProviderUsageRows == null ? List.<ProviderConfigPo>of() : existingProviderUsageRows)
            .stream()
            .map(convertor::toProviderUsageKey)
            .filter(usage -> usage.getProviderName() != null && usage.getProviderType() != null)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        orderedProviders.stream()
            .map(provider -> new ProviderUsageKey(
                provider.getProviderName(),
                ProviderType.fromDbValue(provider.getProviderType())
            ))
            .filter(usage -> usage.getProviderName() != null && usage.getProviderType() != null)
            .filter(usage -> !validProviderUsages.contains(usage))
            .distinct()
            .forEach(usage -> log.info(
                "Skip provider insert because preset table does not contain the provider usage. providerName={}, providerType={}",
                usage.getProviderName(),
                usage.getProviderType().getDbValue()
            ));

        Set<ProviderUsageKey> insertedProviderUsages = new LinkedHashSet<>();
        return orderedProviders.stream()
            .filter(provider -> validProviderUsages.contains(new ProviderUsageKey(
                provider.getProviderName(),
                ProviderType.fromDbValue(provider.getProviderType())
            )))
            .filter(provider -> insertedProviderUsages.add(new ProviderUsageKey(
                provider.getProviderName(),
                ProviderType.fromDbValue(provider.getProviderType())
            )))
            .toList();
    }
}
