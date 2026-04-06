package com.sailv.terraform.analysis.infrastructure.database.convertor;

import com.sailv.terraform.analysis.domain.model.ProviderAction;
import com.sailv.terraform.analysis.domain.model.ProviderConfig;
import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.ProviderUsageKey;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TemplateQuotaResource;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderConfigPo;
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

    @Mapping(target = "providerType", expression = "java(toProviderType(source.getProviderType()))")
    ProviderAction toProviderAction(ProviderActionPo source);

    List<ProviderAction> toProviderActions(Collection<ProviderActionPo> sources);

    @Mapping(target = "providerType", expression = "java(toProviderType(source.getProviderType()))")
    @Mapping(target = "resourceType", source = "quotaResourceType")
    @Mapping(target = "primaryQuotaSubject", expression = "java(toPrimaryQuotaSubject(source.getIsPrimaryQuotaSubject()))")
    ProviderConfig toProviderConfig(ProviderConfigPo source);

    List<ProviderConfig> toProviderConfigs(Collection<ProviderConfigPo> sources);

    default ProviderUsageKey toProviderUsageKey(ProviderActionPo source) {
        if (source == null) {
            return null;
        }
        return new ProviderUsageKey(
            source.getProviderName(),
            toProviderType(source.getProviderType())
        );
    }

    default ProviderUsageKey toProviderUsageKey(ProviderConfigPo source) {
        if (source == null) {
            return null;
        }
        return new ProviderUsageKey(
            source.getProviderName(),
            toProviderType(source.getProviderType())
        );
    }

    default ProviderUsageKey toProviderUsageKey(TemplateProvider source) {
        if (source == null) {
            return null;
        }
        return new ProviderUsageKey(
            source.getProviderName(),
            toProviderType(source.getProviderType())
        );
    }

    @Mapping(target = "id", expression = "java(randomUuid())")
    @Mapping(target = "createTime", expression = "java(now())")
    @Mapping(target = "updateTime", expression = "java(now())")
    TemplateProviderPo toTemplateProviderPo(TemplateProvider source);

    List<TemplateProviderPo> toTemplateProviderPos(Collection<TemplateProvider> sources);

    @Mapping(target = "id", expression = "java(randomUuid())")
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

    default ProviderType toProviderType(String providerType) {
        return ProviderType.fromDbValue(providerType);
    }

    default boolean toPrimaryQuotaSubject(Integer value) {
        return value == null || value != 0;
    }
}
