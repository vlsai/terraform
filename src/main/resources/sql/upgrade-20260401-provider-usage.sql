ALTER TABLE t_mp_provider_actions
    ADD COLUMN provider_type VARCHAR(16) NOT NULL DEFAULT 'resource' AFTER provider_name;

DROP INDEX uk_provider_action ON t_mp_provider_actions;

CREATE UNIQUE INDEX uk_provider_action
    ON t_mp_provider_actions(provider_name, provider_type, action_name);

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

INSERT INTO t_mp_provider_config (
    id,
    provider_name,
    provider_type,
    is_primary_quota_subject,
    quota_resource_type,
    quota_type_hint,
    create_time,
    update_time
)
SELECT
    REPLACE(UUID(), '-', '') AS id,
    provider_name,
    provider_type,
    MAX(is_primary_quota_subject) AS is_primary_quota_subject,
    MAX(resource_type) AS quota_resource_type,
    MAX(quota_type_hint) AS quota_type_hint,
    NOW() AS create_time,
    NOW() AS update_time
FROM t_mp_provider_actions
GROUP BY provider_name, provider_type
ON DUPLICATE KEY UPDATE
    provider_type = VALUES(provider_type),
    is_primary_quota_subject = VALUES(is_primary_quota_subject),
    quota_resource_type = VALUES(quota_resource_type),
    quota_type_hint = VALUES(quota_type_hint),
    update_time = NOW();

ALTER TABLE t_mp_provider_actions
    DROP COLUMN quota_type_hint,
    DROP COLUMN is_primary_quota_subject;
