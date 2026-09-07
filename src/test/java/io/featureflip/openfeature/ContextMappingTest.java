package io.featureflip.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Value;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an OpenFeature context becomes a Featureflip one.
 *
 * <p>The identity handling is the part with teeth. Featureflip resolves the
 * attribute names {@code userId} and {@code user_id} from its dedicated user-id
 * field and never from the attribute map, so an identity left among the
 * attributes is not merely untidy — it is unreadable by every targeting rule
 * written against it, silently.
 */
class ContextMappingTest {

    private static io.featureflip.client.EvaluationContext map(EvaluationContext context) {
        return ContextMapping.toFeatureflipContext(context);
    }

    @Test
    void theTargetingKeyBecomesTheUserId() {
        io.featureflip.client.EvaluationContext mapped = map(new ImmutableContext("user-123"));

        assertThat(mapped.getUserId()).isEqualTo("user-123");
        assertThat(mapped.getAttribute("userId")).isEqualTo("user-123");
    }

    @Test
    void anExplicitIdentityAttributeBeatsTheTargetingKey() {
        // A caller who set both meant the explicit one.
        MutableContext context = new MutableContext("from-targeting-key");
        context.add("user_id", "from-attribute");

        assertThat(map(context).getUserId()).isEqualTo("from-attribute");
    }

    @Test
    void snakeCaseWinsOverCamelCaseBetweenTheTwoSpellings() {
        MutableContext context = new MutableContext();
        context.add("userId", "camel");
        context.add("user_id", "snake");

        assertThat(map(context).getUserId()).isEqualTo("snake");
    }

    @Test
    void camelCaseIsUsedWhenItIsTheOnlySpellingPresent() {
        MutableContext context = new MutableContext();
        context.add("userId", "camel");

        assertThat(map(context).getUserId()).isEqualTo("camel");
    }

    @Test
    void noIdentityAtAllProducesAnAnonymousContext() {
        // Not an empty-string identity: builder("") claims a present-but-empty one,
        // whose analytics events carry `"userId": ""`.
        MutableContext context = new MutableContext();
        context.add("plan", "pro");

        io.featureflip.client.EvaluationContext mapped = map(context);

        assertThat(mapped.getUserId()).isNull();
        assertThat(mapped.getAttribute("plan")).isEqualTo("pro");
    }

    @Test
    void anEmptyTargetingKeyIsTreatedAsAbsent() {
        assertThat(map(new ImmutableContext("")).getUserId()).isNull();
    }

    @Test
    void aNullContextMapsToAnEmptyAnonymousContext() {
        io.featureflip.client.EvaluationContext mapped = map(null);

        assertThat(mapped.getUserId()).isNull();
        assertThat(mapped.getAttribute("anything")).isNull();
    }

    @Test
    void theReservedTargetingKeyDoesNotSurviveAsAnAttribute() {
        // ImmutableContext stores the targeting key in its own attribute map, so
        // copying attributes across verbatim would leave a stray `targetingKey`
        // attribute that targeting rules could match on by accident.
        Map<String, Value> attributes = new LinkedHashMap<>();
        attributes.put("plan", new Value("pro"));

        io.featureflip.client.EvaluationContext mapped =
                map(new ImmutableContext("user-1", attributes));

        assertThat(mapped.getAttribute("targetingKey")).isNull();
        assertThat(mapped.getUserId()).isEqualTo("user-1");
    }

    @Test
    void aNumericIdentityIsStringifiedRatherThanDropped() {
        // A numeric user id is a normal thing to carry, and discarding it would
        // silently move every percentage rollout for that caller.
        MutableContext context = new MutableContext();
        context.add("user_id", 12345);

        assertThat(map(context).getUserId()).isEqualTo("12345");
    }

    @Test
    void aWholeNumericIdentityCarriesNoDecimalPart() {
        // Not cosmetic. The identity is hashed as text to pick a bucket, so "12345"
        // and "12345.0" roll out differently — the same user would land on different
        // sides of a percentage rollout depending on whether their id arrived as a
        // number or a string.
        MutableContext numeric = new MutableContext();
        numeric.add("user_id", 12345);
        MutableContext text = new MutableContext();
        text.add("user_id", "12345");

        assertThat(map(numeric).getUserId()).isEqualTo(map(text).getUserId());
    }

    @Test
    void aFractionalIdentityKeepsItsDecimalPart() {
        MutableContext context = new MutableContext();
        context.add("user_id", 1.5d);

        assertThat(map(context).getUserId()).isEqualTo("1.5");
    }

    @Test
    void scalarAttributesAreUnwrappedToPlainJavaValues() {
        MutableContext context = new MutableContext("user-1");
        context.add("plan", "pro");
        context.add("beta", true);
        context.add("seats", 12);
        context.add("ratio", 0.5d);

        io.featureflip.client.EvaluationContext mapped = map(context);

        assertThat(mapped.getAttribute("plan")).isEqualTo("pro");
        assertThat(mapped.getAttribute("beta")).isEqualTo(Boolean.TRUE);
        // Numbers arrive as doubles: OpenFeature's Value holds integers and doubles in
        // one slot and the evaluator's numeric operators compare numerically, so
        // narrowing here would only risk losing precision.
        assertThat(mapped.getAttribute("seats")).isEqualTo(12.0d);
        assertThat(mapped.getAttribute("ratio")).isEqualTo(0.5d);
    }

    @Test
    void listAndStructureAttributesAreUnwrappedRecursively() {
        Map<String, Value> nested = new LinkedHashMap<>();
        nested.put("tier", new Value("gold"));

        MutableContext context = new MutableContext("user-1");
        context.add("groups", Arrays.asList(new Value("a"), new Value("b")));
        context.add("account", new ImmutableStructure(nested));

        io.featureflip.client.EvaluationContext mapped = map(context);

        assertThat(mapped.getAttribute("groups")).isEqualTo(Arrays.asList("a", "b"));
        assertThat(mapped.getAttribute("account")).isEqualTo(Map.of("tier", "gold"));
    }
}
