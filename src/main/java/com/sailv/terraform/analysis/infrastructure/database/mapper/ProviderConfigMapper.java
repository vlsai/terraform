package com.sailv.terraform.analysis.infrastructure.database.mapper;

import com.sailv.terraform.analysis.domain.model.ProviderUsageKey;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderConfigPo;

import java.util.Collection;
import java.util.List;

/**
 * provider 配额配置数据库访问接口。
 *
 * <p>当前数据源为 `t_mp_provider_config`。
 */
public interface ProviderConfigMapper {

    List<ProviderConfigPo> selectByProviderUsages(Collection<ProviderUsageKey> providerUsages);

    List<ProviderConfigPo> selectExistingProviderUsages(Collection<ProviderUsageKey> providerUsages);
}
