package io.featureflip.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.featureflip.client.EvaluationDetail;
import io.featureflip.client.EvaluationReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every decision the provider makes about one evaluation, tested against a
 * hand-built detail rather than a live client — so the reason table, the type
 * guards and the error/success split are each pinned on their own.
 */
class ResolutionMappingTest {

    private static EvaluationDetail<Object> served(Object value, EvaluationReason reason) {
        return new EvaluationDetail<>(value, reason, null, null, "on");
    }

    // --- reason table -----------------------------------------------------

    @Test
    void mapsEachFeatureflipReasonToItsOpenFeatureCounterpart() {
        assertThat(ResolutionMapping.mapReason(EvaluationReason.RULE_MATCH))
                .isEqualTo(Reason.TARGETING_MATCH.toString());
        assertThat(ResolutionMapping.mapReason(EvaluationReason.FALLTHROUGH))
                .isEqualTo(Reason.DEFAULT.toString());
        assertThat(ResolutionMapping.mapReason(EvaluationReason.FLAG_DISABLED))
                .isEqualTo(Reason.DISABLED.toString());
    }

    @Test
    void surfacesAnUnmetPrerequisiteAsItsOwnReason() {
        // No standard OpenFeature reason models this. Reasons are open strings, so it
        // is reported verbatim rather than mislabelled as DEFAULT or DISABLED.
        assertThat(ResolutionMapping.mapReason(EvaluationReason.PREREQUISITE_FAILED))
                .isEqualTo("PREREQUISITE_FAILED");
    }

    @Test
    void anUnrecognisedReasonDegradesToUnknownRatherThanBeingGuessedAt() {
        // A reason added to the SDK later must not be silently reported as something
        // it is not.
        assertThat(ResolutionMapping.mapReason(null)).isEqualTo(Reason.UNKNOWN.toString());
    }

    // --- the error/success split -----------------------------------------

    @Test
    void anUnmetPrerequisiteYieldsTheOffVariationValueNotAnError() {
        // The load-bearing half of the split: a prerequisite failure serves the flag's
        // off variation, which is a real, correctly typed value the caller should
        // receive. Treating it as an error would replace it with the default.
        ProviderEvaluation<Boolean> evaluation = ResolutionMapping.resolve(
                "gated", true, served(false, EvaluationReason.PREREQUISITE_FAILED),
                ResolutionMapping::asBoolean, "boolean");

        assertThat(evaluation.getValue()).isFalse();
        assertThat(evaluation.getReason()).isEqualTo("PREREQUISITE_FAILED");
        assertThat(evaluation.getErrorCode()).isNull();
    }

    @Test
    void anUnknownFlagYieldsTheCallersDefaultAndFlagNotFound() {
        ProviderEvaluation<String> evaluation = ResolutionMapping.resolve(
                "missing", "fallback",
                new EvaluationDetail<>(null, EvaluationReason.FLAG_NOT_FOUND, null, null),
                ResolutionMapping::asString, "string");

        assertThat(evaluation.getValue()).isEqualTo("fallback");
        assertThat(evaluation.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
        assertThat(evaluation.getReason()).isEqualTo(Reason.ERROR.toString());
    }

    @Test
    void anEvaluationErrorIsReportedAsGeneralNotAsATypeMismatch() {
        // The type guard is SKIPPED on an error reason. The detail's value there is
        // the caller's own default, so guarding it would relabel a genuine failure —
        // or a value of a type the caller never asked about — as TYPE_MISMATCH.
        ProviderEvaluation<Boolean> evaluation = ResolutionMapping.resolve(
                "broken", true,
                new EvaluationDetail<>(null, EvaluationReason.ERROR, null, "boom"),
                ResolutionMapping::asBoolean, "boolean");

        assertThat(evaluation.getValue()).isTrue();
        assertThat(evaluation.getErrorCode()).isEqualTo(ErrorCode.GENERAL);
        assertThat(evaluation.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void aWrongTypedValueIsReportedAsATypeMismatch() {
        // The whole reason the provider reads values as Object: the SDK's typed
        // accessors would have folded this into ERROR, indistinguishable from above.
        ProviderEvaluation<Boolean> evaluation = ResolutionMapping.resolve(
                "gate", true, served("not-a-boolean", EvaluationReason.FALLTHROUGH),
                ResolutionMapping::asBoolean, "boolean");

        assertThat(evaluation.getValue()).isTrue();
        assertThat(evaluation.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
        assertThat(evaluation.getErrorMessage()).contains("boolean");
    }

    @Test
    void aSuccessfulResolutionCarriesTheVariantAndDiagnostics() {
        EvaluationDetail<Object> detail = new EvaluationDetail<>(
                true, EvaluationReason.RULE_MATCH, "rule-7", null, "on", "base-flag");

        ProviderEvaluation<Boolean> evaluation = ResolutionMapping.resolve(
                "gate", false, detail, ResolutionMapping::asBoolean, "boolean");

        assertThat(evaluation.getValue()).isTrue();
        assertThat(evaluation.getVariant()).isEqualTo("on");
        assertThat(evaluation.getReason()).isEqualTo(Reason.TARGETING_MATCH.toString());
        assertThat(evaluation.getFlagMetadata().getString("ruleId")).isEqualTo("rule-7");
        assertThat(evaluation.getFlagMetadata().getString("prerequisiteKey")).isEqualTo("base-flag");
    }

    @Test
    void metadataIsAbsentRatherThanEmptyWhenThereIsNothingToReport() {
        ProviderEvaluation<Boolean> evaluation = ResolutionMapping.resolve(
                "gate", false, served(true, EvaluationReason.FALLTHROUGH),
                ResolutionMapping::asBoolean, "boolean");

        assertThat(evaluation.getFlagMetadata()).isNull();
    }

    // --- type guards ------------------------------------------------------

    @Test
    void integerAcceptsAWholeNumberWrittenAsADouble() {
        // JSON does not distinguish 1 from 1.0, so rejecting the latter would make an
        // integer flag unreadable for a reason its author never chose.
        assertThat(ResolutionMapping.asInteger(42)).isEqualTo(42);
        assertThat(ResolutionMapping.asInteger(42.0d)).isEqualTo(42);
        assertThat(ResolutionMapping.asInteger(42L)).isEqualTo(42);
    }

    @Test
    void integerRejectsAFractionalOrOutOfRangeNumber() {
        assertThat(ResolutionMapping.asInteger(42.5d)).isNull();
        assertThat(ResolutionMapping.asInteger(Long.MAX_VALUE)).isNull();
        assertThat(ResolutionMapping.asInteger("42")).isNull();
    }

    @Test
    void doubleAcceptsAnyNumberIncludingAnIntegralOne() {
        assertThat(ResolutionMapping.asDouble(1)).isEqualTo(1.0d);
        assertThat(ResolutionMapping.asDouble(1.5d)).isEqualTo(1.5d);
        assertThat(ResolutionMapping.asDouble(true)).isNull();
    }

    @Test
    void objectAcceptsStructuresAndArraysAndRejectsScalars() {
        // A caller reaching for an object value wants structure. OpenFeature's Value
        // would happily wrap a scalar and hide the mistake.
        assertThat(ResolutionMapping.asStructure(Map.of("a", 1))).isNotNull();
        assertThat(ResolutionMapping.asStructure(Arrays.asList(1, 2))).isNotNull();
        assertThat(ResolutionMapping.asStructure("a string")).isNull();
        assertThat(ResolutionMapping.asStructure(7)).isNull();
        assertThat(ResolutionMapping.asStructure(true)).isNull();
    }

    @Test
    void objectValuesConvertToNestedOpenFeatureValues() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("enabled", true);
        nested.put("ratio", 0.25d);
        Map<String, Object> served = new LinkedHashMap<>();
        served.put("name", "dark");
        served.put("levels", Arrays.asList(1, 2));
        served.put("nested", nested);

        Value value = ResolutionMapping.asStructure(served);

        assertThat(value).isNotNull();
        assertThat(value.asStructure().getValue("name").asString()).isEqualTo("dark");
        assertThat(value.asStructure().getValue("levels").asList())
                .extracting(Value::asInteger).containsExactly(1, 2);
        assertThat(value.asStructure().getValue("nested").asStructure().getValue("enabled").asBoolean())
                .isTrue();
        assertThat(value.asStructure().getValue("nested").asStructure().getValue("ratio").asDouble())
                .isEqualTo(0.25d);
    }

    @Test
    void booleanAndStringGuardsRejectEachOther() {
        assertThat(ResolutionMapping.asBoolean("true")).isNull();
        assertThat(ResolutionMapping.asString(true)).isNull();
        assertThat(ResolutionMapping.asBoolean(true)).isTrue();
        assertThat(ResolutionMapping.asString("x")).isEqualTo("x");
    }
}
