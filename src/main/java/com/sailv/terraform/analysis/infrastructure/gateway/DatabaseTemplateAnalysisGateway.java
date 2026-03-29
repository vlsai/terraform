package com.sailv.terraform.analysis.infrastructure.gateway;

import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.infrastructure.database.convertor.TemplateAnalysisDataConvertor;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderActionMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionLookupPo;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
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
 * <p>provider action 查询实现了批量查询和本地缓存，避免模板解析时出现重复数据库往返。
 */
public class DatabaseTemplateAnalysisGateway implements TemplateAnalysisGateway {

    private static final Logger LOGGER = Logger.getLogger(DatabaseTemplateAnalysisGateway.class.getName());

    private final ProviderActionMapper providerActionMapper;
    private final TemplateProviderMapper templateProviderMapper;
    private final TemplateQuotaResourceMapper templateQuotaResourceMapper;
    private final TemplateAnalysisDataConvertor convertor;
    private final Map<String, Optional<ProviderActionDefinition>> cache = new ConcurrentHashMap<>();

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
    public Optional<ProviderActionDefinition> findByProviderNameAndActionName(TerraformAction action) {
        Objects.requireNonNull(action, "action cannot be null");
        String key = keyOf(action.providerName(), action.actionName());
        Optional<ProviderActionDefinition> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        ProviderActionPo po = providerActionMapper.selectByProviderNameAndActionName(action.providerName(), action.actionName());
        Optional<ProviderActionDefinition> resolved = Optional.ofNullable(po).map(convertor::toProviderActionDefinition);
        cache.put(key, resolved);
        return resolved;
    }

    @Override
    public List<ProviderActionDefinition> findByProviderNameAndActionName(Collection<TerraformAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        Map<String, ProviderActionDefinition> resolved = new LinkedHashMap<>();
        Map<String, ProviderActionLookupPo> missingLookups = new LinkedHashMap<>();

        for (TerraformAction action : actions) {
            if (action == null) {
                continue;
            }
            String key = keyOf(action.providerName(), action.actionName());
            Optional<ProviderActionDefinition> cached = cache.get(key);
            if (cached != null) {
                cached.ifPresent(definition -> resolved.putIfAbsent(key, definition));
                continue;
            }
            missingLookups.putIfAbsent(key, convertor.toProviderActionLookupPo(action));
        }

        if (!missingLookups.isEmpty()) {
            Set<String> unresolvedKeys = new LinkedHashSet<>(missingLookups.keySet());
            List<ProviderActionPo> fetched = providerActionMapper.selectByProviderActionKeys(missingLookups.values());
            List<ProviderActionDefinition> definitions = convertor.toProviderActionDefinitions(fetched == null ? List.of() : fetched);
            for (ProviderActionDefinition definition : definitions) {
                String key = keyOf(definition.providerName(), definition.actionName());
                cache.put(key, Optional.of(definition));
                resolved.putIfAbsent(key, definition);
                unresolvedKeys.remove(key);
            }
            unresolvedKeys.forEach(key -> cache.put(key, Optional.empty()));
        }

        return List.copyOf(resolved.values());
    }

    @Override
    public void save(TemplateAnalysisResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        List<TemplateProvider> validatedProviders = validateProvidersBeforeInsert(result.providers());
        if (!validatedProviders.isEmpty()) {
            templateProviderMapper.insertBatch(convertor.toTemplateProviderPos(validatedProviders));
        }
        if (!result.quotaResources().isEmpty()) {
            templateQuotaResourceMapper.insertBatch(convertor.toTemplateQuotaResourcePos(result.quotaResources()));
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
            .map(TemplateProvider::providerName)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedProviderNames.isEmpty()) {
            return List.of();
        }

        List<String> existingProviderNames = providerActionMapper.selectExistingProviderNames(requestedProviderNames);
        Set<String> validProviderNames = new LinkedHashSet<>(existingProviderNames == null ? List.of() : existingProviderNames);

        orderedProviders.stream()
            .map(TemplateProvider::providerName)
            .filter(Objects::nonNull)
            .filter(providerName -> !validProviderNames.contains(providerName))
            .distinct()
            .forEach(providerName -> LOGGER.warning(() ->
                "[PROVIDER_NOT_IN_PRESET_TABLE] Skip provider insert because preset table does not contain it: "
                    + providerName));

        return orderedProviders.stream()
            .filter(provider -> validProviderNames.contains(provider.providerName()))
            .toList();
    }

    private String keyOf(String providerName, String actionName) {
        return providerName + '\u0000' + actionName;
    }
}
