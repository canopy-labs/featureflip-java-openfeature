# Featureflip OpenFeature Provider (Java)

[OpenFeature](https://openfeature.dev) provider for [Featureflip](https://featureflip.io), backed by the Featureflip Java server SDK.

## Installation

### Gradle

```groovy
dependencies {
    implementation 'io.featureflip:featureflip-openfeature:0.1.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.featureflip</groupId>
    <artifactId>featureflip-openfeature</artifactId>
    <version>0.1.0</version>
</dependency>
```

The Featureflip Java SDK and the OpenFeature Java SDK come along as transitive dependencies.

## Quick Start

```java
import dev.openfeature.sdk.*;
import io.featureflip.openfeature.FeatureflipProvider;

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new FeatureflipProvider("your-sdk-key"));

Client client = api.getClient();

boolean enabled = client.getBooleanValue("new-checkout", false,
    new ImmutableContext("user-123", Map.of("plan", new Value("pro"))));
```

Use `setProviderAndWait` rather than `setProvider`. The plain form returns before initialization has finished, so evaluations on the following lines can hand back their defaults.

Pass a `FeatureFlagConfig` to configure the underlying client:

```java
api.setProviderAndWait(new FeatureflipProvider("your-sdk-key",
    FeatureFlagConfig.builder()
        .streaming(true)
        .initTimeout(Duration.ofSeconds(5))
        .build()));
```

### Sharing an existing client

If the same process also evaluates flags through the Featureflip SDK directly, hand the provider your client so both paths share one connection rather than opening a second stream:

```java
FeatureflipClient client = FeatureflipClient.get("your-sdk-key");
api.setProviderAndWait(new FeatureflipProvider(client));
```

You keep ownership: `shutdown()` unsubscribes but leaves your client open.

## Context mapping

OpenFeature's `targetingKey` becomes Featureflip's user id, which is what percentage rollouts bucket on and what `userId`/`user_id` targeting rules read.

An explicit `user_id` or `userId` attribute takes precedence over `targetingKey` — a caller who sets both meant the explicit one — and between the two spellings, `user_id` wins. Every other attribute is passed through for targeting rules and segments to match on.

```java
EvaluationContext context = new MutableContext("user-123")
    .add("plan", "enterprise")
    .add("seats", 42);
```

## Evaluation reasons

| Featureflip | OpenFeature |
|---|---|
| `RULE_MATCH` | `TARGETING_MATCH` |
| `FALLTHROUGH` | `DEFAULT` |
| `FLAG_DISABLED` | `DISABLED` |
| `PREREQUISITE_FAILED` | `PREREQUISITE_FAILED` |
| `FLAG_NOT_FOUND` | `ERROR` + `FLAG_NOT_FOUND` |
| `ERROR` | `ERROR` + `GENERAL` |

`PREREQUISITE_FAILED` is not a standard OpenFeature reason. Reasons are open strings, so it is surfaced verbatim rather than mislabelled as `DEFAULT` or `DISABLED` — the same choice the Node, .NET, Python and Go providers made. It is not an error: an unmet prerequisite serves the flag's off variation, which is a real value your code should receive.

Where a flag's evaluation carries them, `ruleId` and `prerequisiteKey` are attached as flag metadata.

## Types

A flag whose value is not of the type you asked for resolves to your default with `TYPE_MISMATCH`, distinctly from a genuine evaluation failure.

* **Integer** reads accept a whole number written in decimal form. JSON does not distinguish `1` from `1.0`, so an integer flag stays readable however its value happened to be serialized.
* **Object** reads accept objects and arrays only. A string, number or boolean resolves to your default with `TYPE_MISMATCH` — reaching for an object value means you want structure.

## Reacting to flag changes

The provider emits `PROVIDER_CONFIGURATION_CHANGED` when flag configuration changes, carrying the affected flag keys:

```java
client.on(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, details ->
    log.info("flags changed: {}", details.getFlagsChanged()));
```

The initial flag load is not reported — a cold start is not a change; OpenFeature signals that with `PROVIDER_READY`.

## Tracking

```java
client.track("checkout-completed", context,
    new MutableTrackingEventDetails(99.99).add("currency", "USD"));
```

The numeric value is optional and is omitted when absent rather than recorded as zero.

## Behaviour on startup

A failed initial flag load does not fail initialization. The SDK keeps retrying in the background and evaluations serve your defaults until flags arrive, so a transient outage at startup degrades rather than takes the provider down. The timeout is logged.

## Requirements

Java 11 or higher.

## License

Apache-2.0
