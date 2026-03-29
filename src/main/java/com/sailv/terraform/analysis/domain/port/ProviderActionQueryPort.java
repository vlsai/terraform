package com.sailv.terraform.analysis.domain.port;

import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import java.util.Optional;

/**
 * 领域层查询端口。
 *
 * <p>这是迁移到内网项目时最重要的替换点：
 * 你们只需要实现这个接口，把现有数据库查询结果转换成 {@link ProviderActionDefinition} 即可。
 */
public interface ProviderActionQueryPort {

    Optional<ProviderActionDefinition> findByProviderNameAndActionName(String providerName, String actionName);
}
