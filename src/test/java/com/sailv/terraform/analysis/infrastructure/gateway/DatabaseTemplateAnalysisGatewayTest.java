package com.sailv.terraform.analysis.infrastructure.gateway;

import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.ProviderUsageKey;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TemplateQuotaResource;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderActionMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateProviderPo;
import com.sailv.terraform.analysis.infrastructure.database.po.TemplateQuotaResourcePo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseTemplateAnalysisGatewayTest {

    @Test
    void shouldValidateProvidersAgainstPresetTableBeforeInsert() {
        RecordingProviderActionMapper providerActionMapper = new RecordingProviderActionMapper(
            Set.of(new ProviderUsageKey("huaweicloud", ProviderType.RESOURCE))
        );
        RecordingTemplateProviderMapper templateProviderMapper = new RecordingTemplateProviderMapper();
        RecordingTemplateQuotaResourceMapper templateQuotaResourceMapper = new RecordingTemplateQuotaResourceMapper();

        DatabaseTemplateAnalysisGateway gateway = new DatabaseTemplateAnalysisGateway(
            providerActionMapper,
            templateProviderMapper,
            templateQuotaResourceMapper
        );

        gateway.save(new TemplateAnalysisResult(
            "template-save-validation",
            List.of(
                new TemplateProvider("template-save-validation", "huaweicloud", "resource"),
                new TemplateProvider("template-save-validation", "unknowncloud", "resource")
            ),
            List.of(new TemplateQuotaResource("template-save-validation", "ecs", "instance_count", 3))
        ));

        assertEquals(
            Set.of(
                new ProviderUsageKey("huaweicloud", ProviderType.RESOURCE),
                new ProviderUsageKey("unknowncloud", ProviderType.RESOURCE)
            ),
            providerActionMapper.lastQueriedProviderUsages
        );
        assertEquals(1, templateProviderMapper.insertedProviders.size());
        assertEquals("huaweicloud", templateProviderMapper.insertedProviders.getFirst().getProviderName());
        assertEquals(1, templateQuotaResourceMapper.insertedResources.size());
        assertEquals(Integer.valueOf(3), templateQuotaResourceMapper.insertedResources.getFirst().getQuotaRequirement());
    }

    private static final class RecordingProviderActionMapper implements ProviderActionMapper {
        private final Set<ProviderUsageKey> existingProviderUsages;
        private Set<ProviderUsageKey> lastQueriedProviderUsages = Set.of();

        private RecordingProviderActionMapper(Set<ProviderUsageKey> existingProviderUsages) {
            this.existingProviderUsages = new LinkedHashSet<>(existingProviderUsages);
        }

        @Override
        public List<ProviderActionPo> selectByProviderUsages(Collection<ProviderUsageKey> providerUsages) {
            return List.of();
        }

        @Override
        public List<ProviderActionPo> selectExistingProviderUsages(Collection<ProviderUsageKey> providerUsages) {
            lastQueriedProviderUsages = new LinkedHashSet<>(providerUsages);
            return providerUsages.stream()
                .filter(existingProviderUsages::contains)
                .map(usage -> new ProviderActionPo()
                    .setProviderName(usage.getProviderName())
                    .setProviderType(usage.getProviderType().getDbValue())
                    .setIsPrimaryQuotaSubject(1))
                .toList();
        }
    }

    private static final class RecordingTemplateProviderMapper implements TemplateProviderMapper {
        private final List<TemplateProviderPo> insertedProviders = new ArrayList<>();

        @Override
        public int insertBatch(Collection<TemplateProviderPo> providers) {
            insertedProviders.addAll(providers);
            return providers.size();
        }
    }

    private static final class RecordingTemplateQuotaResourceMapper implements TemplateQuotaResourceMapper {
        private final List<TemplateQuotaResourcePo> insertedResources = new ArrayList<>();

        @Override
        public int insertBatch(Collection<TemplateQuotaResourcePo> resources) {
            insertedResources.addAll(resources);
            return resources.size();
        }
    }
}
