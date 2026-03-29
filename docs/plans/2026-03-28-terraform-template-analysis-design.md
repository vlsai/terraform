# Terraform Template Analysis Design

## Goal

Build an embeddable Java library for a DDD-style internal system that:

- parses Terraform templates from `.zip`, `.tf`, and `.tf.json`
- recursively analyzes local modules
- identifies providers actually used by the template
- maps Terraform actions to `provider_type` and `resource_type` through a prebuilt query port backed by `t_mp_provider_actions`
- emits persistence-friendly result objects for:
  - `t_mp_template_providers`
  - `t_mp_template_resource`
- supports persistence wiring through `service -> gateway -> mapper` with MapStruct-based domain-to-PO conversion

The library does not call quota URLs. Database persistence is optional and can be wired through the provided gateway / mapper abstractions.

## Architecture

### Domain

- `TemplateProvider`
- `TemplateQuotaResource`
- `TemplateAnalysisResult`
- `ProviderActionDefinition`
- `QuotaCheckRule`
- `TerraformAction`
- `TerraformTemplateDomain`

### Service

- `TemplateSource`
- `TerraformAnalysisService`
- `TerraformAnalysisServiceImpl`

### Gateway

- `TemplateAnalysisGateway`

### Infrastructure

- `TerraformFileParser`
- `HclTerraformFileParser`
- `JsonTerraformFileParser`
- `ZipExtractor`
- database PO classes
- mapper interfaces
- MapStruct convertor
- gateway implementations backed by mappers

## Parsing Strategy

### `.zip`

- extract to a temporary directory
- locate the effective root module
- if the archive has no Terraform files at the extracted root, collapse through single nested directories first
- recursively traverse referenced local modules
- remote modules are not fetched and are recorded in logs

### `.tf`

- parse Terraform blocks needed by this use case:
  - `terraform.required_providers`
  - `provider`
  - `resource`
  - `data`
  - `module`
- use `hcl4j` behind `TerraformFileParser` so upper layers stay insulated from the concrete HCL library

### `.tf.json`

- use Jackson to read Terraform JSON
- extract the same provider, action, and module metadata as `.tf`

## Domain Mapping

- provider output contains only providers actually used by the template:
  - provider blocks
  - provider prefixes inferred from `resource` and `data`
- `provider_type` and `resource_type` come from `TemplateAnalysisGateway`
- `t_mp_template_providers` is validated against the preset table again during save, so invalid provider rows are not inserted even if the result object is hand-crafted upstream
- `t_mp_template_resource` contains only resource types configured for quota tracking
- `t_mp_template_resource` is aggregated by `(resource_type, quota_type)` and stores the summed `quota_requirement`
- parse anomalies are recorded in logs and do not appear in the returned result
- service is responsible for invoking gateway methods; domain no longer queries the database directly

## Constraints and Assumptions

- local modules are resolved relative to the current module directory
- remote modules are skipped and logged
- quota rules are configured as `resource_type -> url`, with optional `quota_type`
- `count = 2` and `count = local.xxx` contribute to `quota_requirement`
- `var.xxx`, arithmetic expressions, `for_each`, and function expressions are not evaluated; they are logged and treated as the default amount `1`
- when `quota_type` cannot be resolved from upstream mapping or rule configuration, the field remains null and the row is skipped

## Public API Shape

```java
TemplateAnalysisResult result = analyzeService.analyze(
    templateId,
    templateSource,
    quotaCheckRules
);
```

`templateId` uses `String` so it can directly carry UUID values from the host system.

Persistence support:

```java
TemplateAnalysisResult result = analyzeService.analyzeAndSave(
    templateId,
    templateSource,
    quotaCheckRules
);
```

In this flow:

- `TemplateAnalysisGateway` receives domain actions and resolves provider/action mappings
- `TemplateAnalysisGateway` also receives the final domain result for persistence
- before inserting `t_mp_template_providers`, the gateway revalidates provider names against `t_mp_provider_actions`
- MapStruct converts domain objects to PO objects for batch insert mappers

The library supports:

- path-backed sources for on-disk analysis
- byte-backed sources for uploaded content

## Verification Plan

- unit tests for `.tf`
- unit tests for `.tf.json`
- unit tests for `.zip`
- verify recursive module traversal and remote module warnings
- verify quota resource filtering and `quota_requirement` aggregation against configured rules
- verify invalid providers are filtered again during save
