package com.sailv.terraform.analysis.infrastructure.database.mapper;

import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionLookupPo;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;

import java.util.Collection;
import java.util.List;

/**
 * `t_mp_provider_actions` 数据库访问接口。
 *
 * <p>内网项目里可以由 MyBatis XML 或注解方式实现。
 */
public interface ProviderActionMapper {

    ProviderActionPo selectByProviderNameAndActionName(String providerName, String actionName);

    List<ProviderActionPo> selectByProviderActionKeys(Collection<ProviderActionLookupPo> lookupKeys);

    /**
     * 查询预制表里真实存在的 provider。
     *
     * <p>保存 `t_mp_template_providers` 时需要再次校验 provider 是否仍存在于
     * `t_mp_provider_actions`，避免调用方手工构造结果对象后直接写入脏数据。
     */
    List<String> selectExistingProviderNames(Collection<String> providerNames);
}
