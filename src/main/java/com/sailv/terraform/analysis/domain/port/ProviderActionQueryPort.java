package com.sailv.terraform.analysis.domain.port;

import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import java.util.Optional;

public interface ProviderActionQueryPort {

    Optional<ProviderActionDefinition> findByProviderNameAndActionName(String providerName, String actionName);
}
