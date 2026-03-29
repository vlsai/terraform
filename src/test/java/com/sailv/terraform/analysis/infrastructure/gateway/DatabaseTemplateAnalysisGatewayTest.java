package com.sailv.terraform.analysis.infrastructure.gateway;

import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TemplateQuotaResource;
import com.sailv.terraform.analysis.infrastructure.database.mapper.ProviderActionMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateProviderMapper;
import com.sailv.terraform.analysis.infrastructure.database.mapper.TemplateQuotaResourceMapper;
import com.sailv.terraform.analysis.infrastructure.database.po.ProviderActionLookupPo;
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
        RecordingProviderActionMapper providerActionMapper = new RecordingProviderActionMapper(Set.of("huaweicloud"));
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
                new TemplateProvider("template-save-validation", "huaweicloud", "huaweicloud"),
                new TemplateProvider("template-save-validation", "unknowncloud", "unknowncloud")
            ),
            List.of(new TemplateQuotaResource("template-save-validation", "ecs", "instance_count", 3))
        ));

        assertEquals(Set.of("huaweicloud", "unknowncloud"), providerActionMapper.lastQueriedProviderNames);
        assertEquals(1, templateProviderMapper.insertedProviders.size());
        assertEquals("huaweicloud", templateProviderMapper.insertedProviders.getFirst().getProviderName());
        assertEquals(1, templateQuotaResourceMapper.insertedResources.size());
        assertEquals(Integer.valueOf(3), templateQuotaResourceMapper.insertedResources.getFirst().getQuotaRequirement());
    }

    private static final class RecordingProviderActionMapper implements ProviderActionMapper {
        private final Set<String> existingProviderNames;
        private Set<String> lastQueriedProviderNames = Set.of();

        private RecordingProviderActionMapper(Set<String> existingProviderNames) {
            this.existingProviderNames = new LinkedHashSet<>(existingProviderNames);
        }

        @Override
        public ProviderActionPo selectByProviderNameAndActionName(String providerName, String actionName) {
            return null;
        }

        @Override
        public List<ProviderActionPo> selectByProviderActionKeys(Collection<ProviderActionLookupPo> lookupKeys) {
            return List.of();
        }

        @Override
        public List<String> selectExistingProviderNames(Collection<String> providerNames) {
            lastQueriedProviderNames = new LinkedHashSet<>(providerNames);
            return providerNames.stream()
                .filter(existingProviderNames::contains)
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
