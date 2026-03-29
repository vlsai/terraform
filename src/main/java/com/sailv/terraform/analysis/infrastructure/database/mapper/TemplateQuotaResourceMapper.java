package com.sailv.terraform.analysis.infrastructure.database.mapper;

import com.sailv.terraform.analysis.infrastructure.database.po.TemplateQuotaResourcePo;

import java.util.Collection;

/**
 * `t_mp_template_resource` 入库 mapper。
 */
public interface TemplateQuotaResourceMapper {

    int insertBatch(Collection<TemplateQuotaResourcePo> resources);
}
