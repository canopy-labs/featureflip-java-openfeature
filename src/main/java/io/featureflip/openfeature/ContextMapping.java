package io.featureflip.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;

import java.util.List;
import java.util.Map;

import io.featureflip.client.EvaluationContext.Builder;

/** Maps an OpenFeature evaluation context onto a Featureflip one. */
final class ContextMapping {

    /**
     * OpenFeature's reserved identity key. Featureflip carries the identity in a
     * dedicated field, so this must not also survive as an attribute.
     */
    private static final String TARGETING_KEY = EvaluationContext.TARGETING_KEY;

    private static final String USER_ID_SNAKE = "user_id";
    private static final String USER_ID_CAMEL = "userId";

    private ContextMapping() {
    }

    /**
     * Converts {@code context}, treating null as an empty anonymous context.
     *
     * <p>The identity handling is the load-bearing part. Featureflip resolves the
     * attribute names {@code userId} and {@code user_id} from
     * {@code EvaluationContext.getUserId()} and never from the attribute map, so an
     * identity left among the attributes would be silently unreadable by every
     * targeting rule written against it. All three spellings are therefore lifted
     * out.
     *
     * <p>Precedence, matching the Node, .NET, Python and Go providers: an explicit
     * identity attribute beats {@code targetingKey}, because a caller who set both
     * meant the explicit one. Between the two spellings, {@code user_id} wins.
     *
     * <p>They are also REMOVED from the attributes, and the three keys differ in
     * how much that matters. Dropping {@code targetingKey} is observable and
     * necessary: nothing else would stop a targeting rule matching on OpenFeature's
     * reserved key as if it were an ordinary attribute. Dropping the two identity
     * spellings is belt-and-braces — {@code getAttribute} short-circuits both to the
     * identity field, so a copy left in the bag can never be read back today — kept
     * because a duplicate identity in the attribute bag is a shape that would start
     * mattering the moment anything iterates it (an identify event's metadata does
     * exactly that).
     */
    static io.featureflip.client.EvaluationContext toFeatureflipContext(EvaluationContext context) {
        if (context == null) {
            return io.featureflip.client.EvaluationContext.builder().build();
        }

        Map<String, Value> attributes = context.asUnmodifiableMap();
        String userId = resolveIdentity(context, attributes);

        Builder builder = userId != null
                ? io.featureflip.client.EvaluationContext.builder(userId)
                : io.featureflip.client.EvaluationContext.builder();

        for (Map.Entry<String, Value> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (TARGETING_KEY.equals(key) || USER_ID_SNAKE.equals(key) || USER_ID_CAMEL.equals(key)) {
                continue;
            }
            builder.set(key, unwrap(entry.getValue()));
        }
        return builder.build();
    }

    private static String resolveIdentity(EvaluationContext context, Map<String, Value> attributes) {
        String fromSnake = identityString(attributes.get(USER_ID_SNAKE));
        if (fromSnake != null) {
            return fromSnake;
        }
        String fromCamel = identityString(attributes.get(USER_ID_CAMEL));
        if (fromCamel != null) {
            return fromCamel;
        }
        // getTargetingKey() rather than the map entry: an ImmutableContext built with
        // the (targetingKey, attributes) constructor exposes it both ways, but one
        // built some other way need not, and the accessor is the contract.
        String targetingKey = context.getTargetingKey();
        if (targetingKey != null && !targetingKey.isEmpty()) {
            return targetingKey;
        }
        return identityString(attributes.get(TARGETING_KEY));
    }

    /**
     * Renders an identity value as the string Featureflip's user id holds.
     *
     * <p>A non-string is stringified rather than dropped: a numeric user id is a
     * normal thing to carry, and discarding it would silently move every
     * percentage rollout for that caller. An empty string is treated as absent, so
     * the next spelling gets its turn.
     *
     * <p>A whole number renders WITHOUT a decimal part, which is not cosmetic. The
     * identity is a bucketing key, hashed as text, so {@code "12345"} and
     * {@code "12345.0"} land in different buckets — the same user would be rolled
     * out differently depending on whether their id arrived as a number or a
     * string. {@link #unwrap} is deliberately not reused here for that reason: it
     * widens every number to a double, which is right for an attribute the
     * evaluator compares numerically and wrong for a key it hashes.
     */
    private static String identityString(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return renderNumericIdentity(value.asDouble());
        }
        Object unwrapped = unwrap(value);
        if (unwrapped == null) {
            return null;
        }
        String rendered = unwrapped.toString();
        return rendered.isEmpty() ? null : rendered;
    }

    /** A whole number as its integer text; anything else as its ordinary decimal form. */
    private static String renderNumericIdentity(Double number) {
        if (number == null || number.isNaN() || number.isInfinite()) {
            return null;
        }
        // Bounded by the largest double that still represents every integer exactly.
        // Beyond that, "whole" is an artefact of the representation rather than
        // something the caller wrote, so the decimal form is the honest rendering.
        if (number == Math.rint(number) && Math.abs(number) <= 9007199254740992d) {
            return Long.toString(number.longValue());
        }
        return number.toString();
    }

    /** Unwraps an OpenFeature {@link Value} to the plain Java object beneath it. */
    private static Object unwrap(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            // asDouble() for every number, matching the .NET provider: Value stores
            // integers and doubles in one slot and the evaluator's numeric operators
            // compare numerically, so narrowing here would only risk losing precision.
            return value.asDouble();
        }
        if (value.isInstant()) {
            return value.asInstant();
        }
        if (value.isList()) {
            List<Value> list = value.asList();
            java.util.List<Object> unwrapped = new java.util.ArrayList<>(list.size());
            for (Value element : list) {
                unwrapped.add(unwrap(element));
            }
            return unwrapped;
        }
        if (value.isStructure()) {
            Map<String, Value> members = value.asStructure().asUnmodifiableMap();
            Map<String, Object> unwrapped = new java.util.LinkedHashMap<>(members.size());
            for (Map.Entry<String, Value> entry : members.entrySet()) {
                unwrapped.put(entry.getKey(), unwrap(entry.getValue()));
            }
            return unwrapped;
        }
        return value.asObject();
    }
}
