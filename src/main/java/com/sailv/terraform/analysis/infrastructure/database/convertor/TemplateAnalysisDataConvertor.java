package com.sailv.terraform.analysis.infrastructure.database.convertor;

import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TemplateQuotaResource;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionLookupPo;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateProviderPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateQuotaResourcePo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 领域对象和数据库层 PO 的转换器。
 *
 * <p>内网项目可以直接复用该接口，由 MapStruct 生成高性能转换代码。
 */
@Mapper
public interface TemplateAnalysisDataConvertor {

    TemplateAnalysisDataConvertor INSTANCE = Mappers.getMapper(TemplateAnalysisDataConvertor.class);

    @Mapping(target = "providerName", expression = "java(source.getProviderName())")
    @Mapping(target = "actionName", expression = "java(source.getActionName())")
    @Mapping(target = "resourceType", expression = "java(source.getResourceType())")
    @Mapping(target = "providerType", expression = "java(source.getProviderType())")
    @Mapping(target = "quotaType", expression = "java(source.getQuotaType())")
    ProviderActionDefinition toProviderActionDefinition(ProviderActionPo source);

    List<ProviderActionDefinition> toProviderActionDefinitions(Collection<ProviderActionPo> sources);

    @Mapping(target = "providerName", expression = "java(source.providerName())")
    @Mapping(target = "actionName", expression = "java(source.actionName())")
    ProviderActionLookupPo toProviderActionLookupPo(TerraformAction source);

    List<ProviderActionLookupPo> toProviderActionLookupPos(Collection<TerraformAction> sources);

    @Mapping(target = "id", expression = "java(randomUuid())")
    @Mapping(target = "templateId", expression = "java(source.templateId())")
    @Mapping(target = "providerName", expression = "java(source.providerName())")
    @Mapping(target = "providerType", expression = "java(source.providerType())")
    @Mapping(target = "createTime", expression = "java(now())")
    @Mapping(target = "updateTime", expression = "java(now())")
    TemplateProviderPo toTemplateProviderPo(TemplateProvider source);

    List<TemplateProviderPo> toTemplateProviderPos(Collection<TemplateProvider> sources);

    @Mapping(target = "id", expression = "java(randomUuid())")
    @Mapping(target = "templateId", expression = "java(source.templateId())")
    @Mapping(target = "resourceType", expression = "java(source.resourceType())")
    @Mapping(target = "quotaType", expression = "java(source.quotaType())")
    @Mapping(target = "quotaRequirement", expression = "java(source.quotaRequirement())")
    @Mapping(target = "createTime", expression = "java(now())")
    @Mapping(target = "updateTime", expression = "java(now())")
    TemplateQuotaResourcePo toTemplateQuotaResourcePo(TemplateQuotaResource source);

    List<TemplateQuotaResourcePo> toTemplateQuotaResourcePos(Collection<TemplateQuotaResource> sources);

    default String randomUuid() {
        return UUID.randomUUID().toString();
    }

    default LocalDateTime now() {
        return LocalDateTime.now();
    }
}
