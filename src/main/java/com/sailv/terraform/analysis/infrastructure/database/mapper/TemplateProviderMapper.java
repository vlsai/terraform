package com.sailv.terraform.analysis.infrastructure.database.mapper;

import com.sailv.terraform.analysis.infrastructure.database.po.TemplateProviderPo;

import java.util.Collection;

/**
 * `t_mp_template_providers` 入库 mapper。
 */
public interface TemplateProviderMapper {

    int insertBatch(Collection<TemplateProviderPo> providers);
}
