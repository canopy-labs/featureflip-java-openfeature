# Changelog

## 0.2.1 — 2026-10-26

### Changed

- Requires `io.featureflip:featureflip-java` 2.13.0 or later, so the evaluation events behind every flag the provider resolves carry the evaluation reason (`rule-match`, `fallthrough`, `flag-disabled`, `prerequisite-failed`, `error` or `flag-not-found`) and the matched rule id. Featureflip's flag Analytics tab uses them to show which part of the targeting served the traffic. (#4268)

## 0.2.0 — 2026-10-20

### Added

- Requests made through the provider now identify it: `User-Agent: featureflip-java/<version> featureflip-openfeature-java/<version>`, so Featureflip can count OpenFeature usage apart from direct SDK use. This applies when the provider builds its own client (`new FeatureflipProvider(sdkKey[, config])`); the wrapper goes on a copy of your `FeatureFlagConfig`, and a `wrapper(...)` you already set is kept. A client passed to `new FeatureflipProvider(client)` was built before the provider saw it, so its requests are unchanged. (#4150)

### Changed

- Depends on `io.featureflip:featureflip-java` 2.11.0, the release that added `FeatureFlagConfig.Builder.wrapper`.

## 0.1.1 — 2026-09-23

### Changed

- Now built on `io.featureflip:featureflip-java` **2.10.0** (was 2.9.0) and `dev.openfeature:sdk` **1.22.1** (was 1.21.0). Both are `api` dependencies, so an application that doesn't declare `featureflip-java` itself picks up 2.10.0 through this provider. That SDK release keeps the real-time stream alive through an outage: before it, five consecutive connection failures (about 31 seconds) switched the SDK to polling for good, so flag changes arrived up to 30 seconds late until the process restarted. The provider's own code is unchanged.

## 0.1.0 — 2026-09-06

Initial release.

- OpenFeature provider backed by the Featureflip Java server SDK: boolean, string, integer, double and object resolution, with `PREREQUISITE_FAILED` surfaced as its own reason and `ruleId`/`prerequisiteKey` attached as flag metadata.
- `PROVIDER_CONFIGURATION_CHANGED` on every flag-configuration change, carrying the affected keys. The initial load is not reported — a cold start is not a change.
- Tracking through OpenFeature's `track`, with the optional numeric value omitted when absent rather than sent as zero.
- Construct from an SDK key (the provider owns the client) or from an existing `FeatureflipClient` (you keep ownership; `shutdown()` leaves it open).
