# Terraform Template Analysis Design

## Goal

Build an embeddable Java library for a DDD-style internal system that:

- parses Terraform templates from `.zip`, `.tf`, and `.tf.json`
- recursively analyzes local modules
- identifies providers actually used by the template
- maps Terraform actions to `provider_type` and `resource_type` through a prebuilt query port backed by `t_mp_provider_actions`
- emits persistence-friendly result objects for:
  - `t_mp_template_providers`
  - quota-oriented template resources
  - quota check request skeletons

The library does not write to the database and does not call quota URLs.

## Architecture

### Domain

- `TemplateProvider`
- `TemplateQuotaResource`
- `QuotaCheckRequest`
- `TemplateAnalysisResult`
- `ProviderActionDefinition`
- `QuotaCheckRule`
- `AnalysisWarning`
- `ProviderActionQueryPort`
- `TemplateAnalysisDomainService`

### Application

- `TemplateSource`
- `TerraformTemplateAnalyzeService`
- internal discovered models used to bridge parser output to domain mapping

### Infrastructure

- `TerraformFileParser`
- `HclTerraformFileParser`
- `JsonTerraformFileParser`
- `ZipExtractor`

## Parsing Strategy

### `.zip`

- extract to a temporary directory
- locate the effective root module
- if the archive has no Terraform files at the extracted root, collapse through single nested directories first
- recursively traverse referenced local modules
- remote modules are not fetched and produce warnings

### `.tf`

- parse Terraform blocks needed by this use case:
  - `terraform.required_providers`
  - `provider`
  - `resource`
  - `data`
  - `module`
- isolate the HCL parser behind `TerraformFileParser` so it can be swapped for `hcl2j` or another parser later

### `.tf.json`

- use Jackson to read Terraform JSON
- extract the same provider, action, and module metadata as `.tf`

## Domain Mapping

- provider output contains only providers actually used by the template:
  - provider blocks
  - provider prefixes inferred from `resource` and `data`
- `provider_type` and `resource_type` come from `ProviderActionQueryPort`
- quota resource output contains only resource types configured for quota checks
- quota checks are emitted as request skeletons only and are not executed

## Constraints and Assumptions

- local modules are resolved relative to the current module directory
- remote modules are skipped with warnings
- quota rules are configured as `resource_type -> url`, with optional `quota_type`
- when `quota_type` cannot be resolved from upstream mapping or rule configuration, the field remains null and a warning can be emitted by the caller if needed

## Public API Shape

```java
TemplateAnalysisResult result = analyzeService.analyze(
    templateId,
    templateSource,
    quotaCheckRules
);
```

The library supports:

- path-backed sources for on-disk analysis
- byte-backed sources for uploaded content

## Verification Plan

- unit tests for `.tf`
- unit tests for `.tf.json`
- unit tests for `.zip`
- verify recursive module traversal and remote module warnings
- verify quota resource filtering against configured rules
