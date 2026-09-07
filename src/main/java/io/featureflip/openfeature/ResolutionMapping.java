package io.featureflip.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.featureflip.client.EvaluationDetail;
import io.featureflip.client.EvaluationReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a Featureflip {@link EvaluationDetail} into an OpenFeature
 * {@link ProviderEvaluation}.
 *
 * <p>Kept separate from the provider, and free of any client, so that every
 * mapping decision below is directly testable against a hand-built detail rather
 * than only through a live evaluation.
 */
final class ResolutionMapping {

    /**
     * The reason returned when a flag short-circuited because one of its
     * prerequisites did not serve its expected variation.
     *
     * <p>No standard OpenFeature reason models an unmet prerequisite. Reasons are
     * open strings, so this surfaces it verbatim rather than mislabelling it as
     * {@code DEFAULT} or {@code DISABLED} — the same choice the Node, .NET, Python
     * and Go providers made.
     */
    static final String PREREQUISITE_FAILED = "PREREQUISITE_FAILED";

    private ResolutionMapping() {
    }

    /**
     * Decides a value of type {@code T} from the raw JSON value a flag served.
     *
     * <p>Returns null when the served value is not of the requested type, which
     * the caller reports as {@link ErrorCode#TYPE_MISMATCH}. Null is unambiguous
     * here because no guard ever legitimately produces it.
     */
    @FunctionalInterface
    interface TypeGuard<T> {
        T apply(Object value);
    }

    /**
     * Builds the evaluation OpenFeature should see for one resolved flag.
     *
     * @param defaultValue the caller's default, returned on every error path
     * @param guard        decides the requested type from the served value
     * @param expectedType the type name used in a mismatch message
     */
    static <T> ProviderEvaluation<T> resolve(
            String flagKey,
            T defaultValue,
            EvaluationDetail<Object> detail,
            TypeGuard<T> guard,
            String expectedType) {

        // On an error reason the caller's (correctly typed) default is substituted
        // and the type guard is skipped: the detail's value on those paths is the
        // caller's own default, and guarding it would mislabel a genuine error — or
        // an unknown flag — as TYPE_MISMATCH. Only successful resolutions are checked.
        //
        // PREREQUISITE_FAILED is deliberately NOT one of these: an unmet prerequisite
        // serves the flag's off variation, which is a real, correctly typed value the
        // caller should receive.
        if (detail.getReason() == EvaluationReason.FLAG_NOT_FOUND) {
            return error(defaultValue, ErrorCode.FLAG_NOT_FOUND,
                    message(detail, "Flag '" + flagKey + "' was not found"));
        }
        if (detail.getReason() == EvaluationReason.ERROR) {
            return error(defaultValue, ErrorCode.GENERAL,
                    message(detail, "Flag '" + flagKey + "' could not be evaluated"));
        }

        T value = guard.apply(detail.getValue());
        if (value == null) {
            return error(defaultValue, ErrorCode.TYPE_MISMATCH,
                    "Flag '" + flagKey + "' did not resolve to a " + expectedType + " value");
        }

        return ProviderEvaluation.<T>builder()
                .value(value)
                .variant(detail.getVariationKey())
                .reason(mapReason(detail.getReason()))
                .flagMetadata(buildMetadata(detail.getRuleId(), detail.getPrerequisiteKey()))
                .build();
    }

    /** The caller's default, with the error that displaced the served value. */
    static <T> ProviderEvaluation<T> error(T defaultValue, ErrorCode errorCode, String errorMessage) {
        return ProviderEvaluation.<T>builder()
                .value(defaultValue)
                .reason(Reason.ERROR.toString())
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Translates a Featureflip reason to an OpenFeature one. A reason absent from
     * this table falls through to {@code UNKNOWN} rather than being guessed at, so
     * a reason added to the SDK later degrades instead of lying.
     */
    static String mapReason(EvaluationReason reason) {
        if (reason == null) {
            return Reason.UNKNOWN.toString();
        }
        switch (reason) {
            case RULE_MATCH:
                return Reason.TARGETING_MATCH.toString();
            case FALLTHROUGH:
                return Reason.DEFAULT.toString();
            case FLAG_DISABLED:
                return Reason.DISABLED.toString();
            case PREREQUISITE_FAILED:
                return PREREQUISITE_FAILED;
            default:
                return Reason.UNKNOWN.toString();
        }
    }

    /** Diagnostics OpenFeature has no first-class field for. Null when there are none. */
    static ImmutableMetadata buildMetadata(String ruleId, String prerequisiteKey) {
        if (ruleId == null && prerequisiteKey == null) {
            return null;
        }
        ImmutableMetadata.ImmutableMetadataBuilder builder = ImmutableMetadata.builder();
        if (ruleId != null) {
            builder.addString("ruleId", ruleId);
        }
        if (prerequisiteKey != null) {
            builder.addString("prerequisiteKey", prerequisiteKey);
        }
        return builder.build();
    }

    // --- Type guards ------------------------------------------------------
    //
    // Flag values arrive as decoded JSON, so a number is whatever shape Jackson
    // chose for how it was written. The numeric guards accept the integral forms
    // too, so that a flag's readability does not depend on how its value happened
    // to be serialized.

    static Boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * Accepts a whole-number double (1.0, 1e2) as well as the integer types,
     * mirroring the .NET, Python and Go providers. JSON does not distinguish 1
     * from 1.0, so rejecting the latter would make an integer flag unreadable for
     * reasons its author never chose.
     */
    static Integer asInteger(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            long asLong = (Long) value;
            return asLong == (int) asLong ? (int) asLong : null;
        }
        if (value instanceof Double || value instanceof Float) {
            double asDouble = ((Number) value).doubleValue();
            return asDouble == Math.rint(asDouble) && asDouble == (int) asDouble ? (int) asDouble : null;
        }
        return null;
    }

    /** Accepts any JSON number, including an integral one — a flag holding 1 is a perfectly good double. */
    static Double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    /**
     * Accepts objects and arrays only. A string, number or boolean is deliberately
     * rejected: a caller reaching for an object value wants structure, and
     * OpenFeature's {@link Value} would happily wrap a scalar and hide the mistake.
     */
    static Value asStructure(Object value) {
        if (value instanceof Map || value instanceof List) {
            return toValue(value);
        }
        return null;
    }

    /** Converts a decoded JSON value to OpenFeature's {@link Value}. */
    static Value toValue(Object value) {
        if (value == null) {
            return new Value();
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Value> members = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                members.put(String.valueOf(entry.getKey()), toValue(entry.getValue()));
            }
            return new Value(new ImmutableStructure(members));
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Value> elements = new ArrayList<>(list.size());
            for (Object element : list) {
                elements.add(toValue(element));
            }
            return new Value(elements);
        }
        if (value instanceof Boolean) {
            return new Value((Boolean) value);
        }
        if (value instanceof String) {
            return new Value((String) value);
        }
        if (value instanceof Integer) {
            return new Value((Integer) value);
        }
        if (value instanceof Number) {
            return new Value(((Number) value).doubleValue());
        }
        // Nothing else can come out of a JSON document, but a Value that silently
        // dropped an unexpected shape would be worse than one that stringifies it.
        return new Value(value.toString());
    }

    /** The SDK's own message where it has one, so a diagnostic is not thrown away. */
    private static String message(EvaluationDetail<Object> detail, String fallback) {
        String errorMessage = detail.getErrorMessage();
        return errorMessage != null && !errorMessage.isEmpty() ? errorMessage : fallback;
    }
}
