ALTER TABLE t_mp_provider_actions
    ADD COLUMN provider_type VARCHAR(16) NOT NULL DEFAULT 'resource' AFTER provider_name,
    ADD COLUMN quota_type_hint VARCHAR(64) DEFAULT NULL AFTER resource_type,
    ADD COLUMN is_primary_quota_subject TINYINT(1) NOT NULL DEFAULT 0 AFTER quota_type_hint;

DROP INDEX uk_provider_action ON t_mp_provider_actions;

CREATE UNIQUE INDEX uk_provider_action
    ON t_mp_provider_actions(provider_name, provider_type, action_name);
