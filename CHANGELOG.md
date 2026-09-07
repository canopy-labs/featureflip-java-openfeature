# Changelog

## 0.1.0 — 2026-09-06

Initial release.

- OpenFeature provider backed by the Featureflip Java server SDK: boolean, string, integer, double and object resolution, with `PREREQUISITE_FAILED` surfaced as its own reason and `ruleId`/`prerequisiteKey` attached as flag metadata.
- `PROVIDER_CONFIGURATION_CHANGED` on every flag-configuration change, carrying the affected keys. The initial load is not reported — a cold start is not a change.
- Tracking through OpenFeature's `track`, with the optional numeric value omitted when absent rather than sent as zero.
- Construct from an SDK key (the provider owns the client) or from an existing `FeatureflipClient` (you keep ownership; `shutdown()` leaves it open).
