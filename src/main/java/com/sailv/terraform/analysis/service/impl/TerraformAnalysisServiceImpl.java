package com.sailv.terraform.analysis.service.impl;

import com.sailv.terraform.analysis.application.factory.TerraformTemplateFactory;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TerraformTemplate;
import com.sailv.terraform.analysis.gateway.TemplateAnalysisGateway;
import com.sailv.terraform.analysis.service.TerraformAnalysisService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Service 实现。
 *
 * <p>这里保留最薄的一层编排：
 * <ul>
 *     <li>调用工厂把上传文件解析成领域对象</li>
 *     <li>调用 gateway 查询预制表映射</li>
 *     <li>调用领域对象生成最终入库结果</li>
 * </ul>
 */
public class TerraformAnalysisServiceImpl implements TerraformAnalysisService {

    private final TerraformTemplateFactory terraformTemplateFactory;
    private final TemplateAnalysisGateway templateAnalysisGateway;

    public TerraformAnalysisServiceImpl(TemplateAnalysisGateway templateAnalysisGateway) {
        this(new TerraformTemplateFactory(), templateAnalysisGateway);
    }

    public TerraformAnalysisServiceImpl(
        TerraformTemplateFactory terraformTemplateFactory,
        TemplateAnalysisGateway templateAnalysisGateway
    ) {
        this.terraformTemplateFactory = Objects.requireNonNull(terraformTemplateFactory, "terraformTemplateFactory cannot be null");
        this.templateAnalysisGateway = Objects.requireNonNull(templateAnalysisGateway, "templateAnalysisGateway cannot be null");
    }

    public TerraformAnalysisServiceImpl(
        List<com.sailv.terraform.analysis.application.parser.TerraformFileParser> parsers,
        TemplateAnalysisGateway templateAnalysisGateway
    ) {
        this(new TerraformTemplateFactory(parsers), templateAnalysisGateway);
    }

    @Override
    public TemplateAnalysisResult analyze(
        String templateId,
        InputStream inputStream,
        String fileName,
        QuotaCheckRule quotaRules
    ) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream cannot be null");
        Objects.requireNonNull(fileName, "fileName cannot be null");

        TerraformTemplate terraformTemplate = terraformTemplateFactory.create(inputStream, fileName);

        return terraformTemplate.analyze(
            templateId,
            quotaRules,
            templateAnalysisGateway.findByProviderNameAndActionName(terraformTemplate.getActions())
        );
    }

    @Override
    public void save(TemplateAnalysisResult result) {
        templateAnalysisGateway.save(result);
    }
}
