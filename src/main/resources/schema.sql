CREATE TABLE IF NOT EXISTS t_mp_provider_actions (
    provider_name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(16) NOT NULL,
    action_name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_name, provider_type, action_name)
);

CREATE TABLE IF NOT EXISTS t_mp_provider_config (
    id VARCHAR(64) NOT NULL,
    provider_name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(16) NOT NULL,
    is_primary_quota_subject TINYINT(1) NOT NULL DEFAULT 0,
    quota_resource_type VARCHAR(64) DEFAULT NULL,
    quota_type_hint VARCHAR(64) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_config_provider_name (provider_name)
);

CREATE TABLE IF NOT EXISTS t_mp_template_providers (
    id VARCHAR(64) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    provider_name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(16) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_template_providers_template_id (template_id)
);

CREATE TABLE IF NOT EXISTS t_mp_template_resource (
    id VARCHAR(64) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    quota_type VARCHAR(64) NOT NULL,
    quota_requirement INT NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_template_resource_template_id (template_id)
);
