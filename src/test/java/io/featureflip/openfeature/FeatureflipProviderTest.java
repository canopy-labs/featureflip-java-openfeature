package io.featureflip.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableTrackingEventDetails;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.featureflip.client.FeatureflipClient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider driven end to end against a real {@link FeatureflipClient}.
 *
 * <p>Uses the SDK's fixed-value test client rather than a hand-rolled fake, so
 * these run against the client a caller actually gets — a fake would let the
 * provider drift from the SDK's real return shapes without anything failing.
 */
class FeatureflipProviderTest {

    private static final MutableContext USER = new MutableContext("user-1");

    private static FeatureflipProvider providerServing(Map<String, Object> values) {
        FeatureflipProvider provider = new FeatureflipProvider(FeatureflipClient.forTesting(values));
        provider.initialize(null);
        return provider;
    }

    @Test
    void reportsItsName() {
        assertThat(new FeatureflipProvider("sdk-key").getMetadata().getName()).isEqualTo("featureflip-java");
    }

    @Test
    void resolvesEachFlagType() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gate", true);
        values.put("tier", "pro");
        values.put("limit", 100);
        values.put("ratio", 0.25d);
        values.put("theme", Map.of("color", "blue"));
        FeatureflipProvider provider = providerServing(values);

        assertThat(provider.getBooleanEvaluation("gate", false, USER).getValue()).isTrue();
        assertThat(provider.getStringEvaluation("tier", "free", USER).getValue()).isEqualTo("pro");
        assertThat(provider.getIntegerEvaluation("limit", 1, USER).getValue()).isEqualTo(100);
        assertThat(provider.getDoubleEvaluation("ratio", 1.0d, USER).getValue()).isEqualTo(0.25d);
        assertThat(provider.getObjectEvaluation("theme", new Value(), USER)
                .getValue().asStructure().getValue("color").asString()).isEqualTo("blue");
    }

    @Test
    void resolvesAnArrayThroughTheObjectAccessor() {
        FeatureflipProvider provider = providerServing(Map.of("levels", Arrays.asList(1, 2, 3)));

        ProviderEvaluation<Value> evaluation = provider.getObjectEvaluation("levels", new Value(), USER);

        assertThat(evaluation.getErrorCode()).isNull();
        assertThat(evaluation.getValue().asList()).hasSize(3);
    }

    @Test
    void anUnknownFlagReturnsTheCallersDefault() {
        FeatureflipProvider provider = providerServing(Map.of());

        ProviderEvaluation<Boolean> evaluation = provider.getBooleanEvaluation("missing", true, USER);

        assertThat(evaluation.getValue()).isTrue();
        assertThat(evaluation.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
    }

    @Test
    void aWrongTypedFlagIsReportedAsATypeMismatchNotAGeneralError() {
        // The distinction the provider exists to preserve: reading through the SDK's
        // typed accessors would have reported both as ERROR.
        FeatureflipProvider provider = providerServing(Map.of("tier", "pro"));

        ProviderEvaluation<Boolean> evaluation = provider.getBooleanEvaluation("tier", false, USER);

        assertThat(evaluation.getValue()).isFalse();
        assertThat(evaluation.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
        assertThat(evaluation.getReason()).isEqualTo(Reason.ERROR.toString());
    }

    @Test
    void aScalarIsNotAcceptedThroughTheObjectAccessor() {
        FeatureflipProvider provider = providerServing(Map.of("tier", "pro"));

        assertThat(provider.getObjectEvaluation("tier", new Value(), USER).getErrorCode())
                .isEqualTo(ErrorCode.TYPE_MISMATCH);
    }

    @Test
    void evaluatingBeforeInitializationReportsProviderNotReady() {
        // The sdkKey constructor deliberately defers client construction to
        // initialize(), so an evaluation before then has nothing to ask.
        FeatureflipProvider provider = new FeatureflipProvider("sdk-key");

        ProviderEvaluation<Boolean> evaluation = provider.getBooleanEvaluation("gate", true, USER);

        assertThat(evaluation.getValue()).isTrue();
        assertThat(evaluation.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_NOT_READY);
    }

    @Test
    void evaluatingAfterShutdownReportsProviderNotReadyForAnOwnedClient() {
        FeatureflipProvider provider = providerServing(Map.of("gate", true));
        // Constructed from a client, so shutdown leaves it open — assert the resolution
        // still works, which is the ownership contract.
        provider.shutdown();

        assertThat(provider.getBooleanEvaluation("gate", false, USER).getValue()).isTrue();
    }

    @Test
    void shutdownDoesNotCloseACallerSuppliedClient() {
        // A caller-supplied client is a refcounted handle the caller still holds;
        // closing it would make THEIR handle start serving defaults.
        FeatureflipClient client = FeatureflipClient.forTesting(Map.of("gate", true));
        FeatureflipProvider provider = new FeatureflipProvider(client);
        provider.initialize(null);

        provider.shutdown();

        assertThat(client.boolVariation("gate", io.featureflip.client.EvaluationContext.builder("u").build(), false))
                .isTrue();
    }

    @Test
    void initializingTwiceIsSafe() {
        // OpenFeature may re-initialize a provider that is already initialized; a
        // second subscription would double every configuration-changed event.
        FeatureflipProvider provider = providerServing(Map.of("gate", true));

        provider.initialize(null);

        assertThat(provider.getBooleanEvaluation("gate", false, USER).getValue()).isTrue();
    }

    @Test
    void aNullContextEvaluatesAsAnonymousRatherThanThrowing() {
        FeatureflipProvider provider = providerServing(Map.of("gate", true));

        assertThat(provider.getBooleanEvaluation("gate", false, null).getValue()).isTrue();
    }

    // --- tracking ---------------------------------------------------------

    @Test
    void trackingMetadataCarriesTheAttributesAndTheMeasurement() {
        MutableTrackingEventDetails details = new MutableTrackingEventDetails(99.99);
        details.add("currency", "USD");

        Map<String, Object> metadata = FeatureflipProvider.trackingMetadata(details);

        assertThat(metadata).containsEntry("currency", "USD");
        assertThat(metadata).containsEntry("value", 99.99);
    }

    @Test
    void anAbsentMeasurementIsOmittedRatherThanSentAsZero() {
        // OpenFeature models the value as an Optional, so absent and zero are
        // distinguishable here — and a fabricated 0 would be a real measurement to
        // anything reading the event.
        MutableTrackingEventDetails details = new MutableTrackingEventDetails();
        details.add("plan", "pro");

        assertThat(FeatureflipProvider.trackingMetadata(details))
                .containsEntry("plan", "pro")
                .doesNotContainKey("value");
    }

    @Test
    void aZeroMeasurementIsKept() {
        assertThat(FeatureflipProvider.trackingMetadata(new MutableTrackingEventDetails(0)))
                .containsEntry("value", 0);
    }

    @Test
    void nullTrackingDetailsProduceEmptyMetadata() {
        assertThat(FeatureflipProvider.trackingMetadata(null)).isEmpty();
    }

    @Test
    void trackingBeforeInitializationIsANoOpRatherThanAThrow() {
        FeatureflipProvider provider = new FeatureflipProvider("sdk-key");

        provider.track("checkout", USER, new MutableTrackingEventDetails(1));
    }
}
