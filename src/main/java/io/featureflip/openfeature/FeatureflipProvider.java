package io.featureflip.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.TrackingEventDetails;
import dev.openfeature.sdk.Value;
import io.featureflip.client.EvaluationDetail;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.FeatureFlagInitializationException;
import io.featureflip.client.FeatureflipClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An OpenFeature provider backed by the Featureflip Java server SDK.
 *
 * <p>Register it with the OpenFeature API and evaluate flags through the standard
 * client:
 *
 * <pre>{@code
 * OpenFeatureAPI api = OpenFeatureAPI.getInstance();
 * api.setProviderAndWait(new FeatureflipProvider("sdk-xxx"));
 *
 * Client client = api.getClient();
 * boolean enabled = client.getBooleanValue("new-checkout", false,
 *     new ImmutableContext("user-123", Map.of("plan", new Value("pro"))));
 * }</pre>
 *
 * <p>Use {@code setProviderAndWait} rather than {@code setProvider}: the plain
 * form returns before initialization has finished, so evaluations on the
 * following lines can hand back their defaults.
 *
 * <p>Construct it either with an SDK key, in which case the provider creates and
 * owns the underlying client, or with an existing {@link FeatureflipClient}, in
 * which case the caller keeps ownership and {@link #shutdown()} leaves it open.
 *
 * <p>Instances are safe for concurrent use.
 */
public final class FeatureflipProvider extends EventProvider {

    private static final Logger log = LoggerFactory.getLogger(FeatureflipProvider.class);

    /** Reported through {@link #getMetadata()}. */
    private static final String PROVIDER_NAME = "featureflip-java";

    private static final String NOT_READY_MESSAGE = "Featureflip provider has not been initialized";

    private final String sdkKey;
    private final FeatureFlagConfig config;
    private final boolean ownsClient;

    /** Guards {@link #client} and {@link #unsubscribe}, which initialize installs and shutdown clears. */
    private final Object lifecycleLock = new Object();

    private FeatureflipClient client;
    private Runnable unsubscribe;

    /**
     * Creates a provider that owns its client, reading the SDK key from
     * {@code FEATUREFLIP_SDK_KEY} when {@code sdkKey} is null or blank.
     *
     * <p>The client is constructed in {@link #initialize(EvaluationContext)}, not
     * here, because construction blocks on the initial flag fetch. Building it in
     * the constructor would move that wait out of {@code setProviderAndWait},
     * where callers expect it.
     */
    public FeatureflipProvider(String sdkKey) {
        this(sdkKey, null);
    }

    /** Creates a provider that owns its client, configured by {@code config}. */
    public FeatureflipProvider(String sdkKey, FeatureFlagConfig config) {
        this.sdkKey = sdkKey;
        this.config = config;
        this.ownsClient = true;
    }

    /**
     * Creates a provider backed by an existing client.
     *
     * <p>The caller retains ownership: {@link #shutdown()} will not close it. Use
     * this when the same process also evaluates flags through the SDK directly, so
     * that both paths share one client rather than opening a second stream.
     */
    public FeatureflipProvider(FeatureflipClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.sdkKey = null;
        this.config = null;
        this.ownsClient = false;
        this.client = client;
    }

    @Override
    public Metadata getMetadata() {
        return () -> PROVIDER_NAME;
    }

    /**
     * Obtains the client if this provider owns one, waits for its initial flag
     * load, and subscribes to later changes.
     *
     * <p>The evaluation context is unused: Featureflip is a dynamic-context
     * (server) provider, so targeting comes from the per-evaluation context rather
     * than being bound at initialization.
     *
     * <p>A failed initial load does NOT fail initialization. The SDK keeps
     * retrying in the background and evaluations fail safe to the caller's default
     * until flags arrive, so taking the whole provider to an error state over a
     * transient outage at startup would trade a degraded system for a dead one.
     * The timeout is logged. This matches the .NET provider.
     *
     * <p>Safe to call more than once; OpenFeature may re-initialize a provider
     * that is already initialized.
     */
    @Override
    public void initialize(EvaluationContext evaluationContext) {
        FeatureflipClient current;
        synchronized (lifecycleLock) {
            if (client == null) {
                client = config != null
                        ? FeatureflipClient.get(sdkKey, config)
                        : FeatureflipClient.get(sdkKey);
            }
            current = client;
        }

        try {
            current.waitForInitialization();
        } catch (FeatureFlagInitializationException e) {
            log.warn("Featureflip flags were not loaded before the init timeout; "
                    + "evaluations will serve their defaults until they arrive: {}", e.getMessage());
        }

        synchronized (lifecycleLock) {
            // Subscribed only once the client is ready, so the initial flag load is
            // never reported as a configuration change — OpenFeature signals that with
            // PROVIDER_READY. Guarded because OpenFeature may re-initialize a provider
            // that is already initialized, and a second subscription would double every
            // event.
            //
            // Subscribing is still correct on the timeout path above: the SDK's store
            // establishes its baseline silently on the first snapshot whenever that
            // lands, so a load still in flight cannot raise a change either.
            if (unsubscribe == null && client != null) {
                unsubscribe = client.onUpdate(this::onFlagsChanged);
            }
        }
    }

    /**
     * Closes the client only if this provider created it.
     *
     * <p>A caller-supplied client is a refcounted handle the caller still holds;
     * closing it would make THEIR handle start serving defaults.
     */
    @Override
    public void shutdown() {
        FeatureflipClient toClose = null;
        synchronized (lifecycleLock) {
            // Unsubscribed regardless of ownership: the listener emits on THIS
            // provider's event channel, so leaving it attached to a caller-owned client
            // would keep emitting after OpenFeature has torn the provider down.
            if (unsubscribe != null) {
                unsubscribe.run();
                unsubscribe = null;
            }
            if (ownsClient && client != null) {
                toClose = client;
                client = null;
            }
        }
        if (toClose != null) {
            toClose.close();
        }
        super.shutdown();
    }

    /**
     * Republishes an SDK flag update as {@code PROVIDER_CONFIGURATION_CHANGED}.
     *
     * <p>Runs on the SDK's streaming or polling thread — the one delivering flags —
     * so it must not block. {@code emitProviderConfigurationChanged} hands the
     * event to OpenFeature's own executor and returns an {@link
     * dev.openfeature.sdk.Awaitable}; that awaitable is deliberately not awaited,
     * because waiting on it here is exactly what would stall flag delivery.
     */
    void onFlagsChanged(List<String> flagKeys) {
        try {
            emitProviderConfigurationChanged(ProviderEventDetails.builder()
                    .flagsChanged(flagKeys)
                    .message("Featureflip flag configuration changed")
                    .build());
        } catch (RuntimeException e) {
            // The SDK logs and swallows a listener's exception, so this would not break
            // flag delivery either way; logging it here is what keeps it from vanishing.
            log.warn("Could not emit PROVIDER_CONFIGURATION_CHANGED for {}: {}", flagKeys, e.toString());
        }
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String key, Boolean defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, ResolutionMapping::asBoolean, "boolean");
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String key, String defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, ResolutionMapping::asString, "string");
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String key, Integer defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, ResolutionMapping::asInteger, "integer");
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String key, Double defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, ResolutionMapping::asDouble, "double");
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String key, Value defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, ResolutionMapping::asStructure, "object");
    }

    /** Records a custom analytics event against the Featureflip client. */
    @Override
    public void track(String trackingEventName, EvaluationContext ctx, TrackingEventDetails details) {
        FeatureflipClient current = currentClient();
        if (current == null) {
            return;
        }

        current.track(trackingEventName, ContextMapping.toFeatureflipContext(ctx),
                trackingMetadata(details));
    }

    /**
     * Flattens OpenFeature's tracking details into the metadata bag Featureflip
     * records.
     *
     * <p>OpenFeature models the numeric measurement separately from the attributes,
     * as an {@link Optional}, so — unlike the Go provider, whose tracking details
     * expose a bare float with no unset state — an absent value is distinguishable
     * from a real zero and is simply omitted rather than sent as 0.
     */
    static Map<String, Object> trackingMetadata(TrackingEventDetails details) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (details == null) {
            return metadata;
        }
        metadata.putAll(details.asObjectMap());
        Optional<Number> value = details.getValue();
        value.ifPresent(number -> metadata.put("value", number));
        return metadata;
    }

    /**
     * Evaluates one flag and maps the result, enforcing the requested type.
     *
     * <p>Reads the value as {@code Object} rather than through a typed accessor,
     * and that is the whole reason {@code jsonVariationDetail} exists. The SDK's
     * typed accessors report a wrong-typed value as {@code ERROR} — the same reason
     * a genuine evaluation failure carries — so a provider built on them could not
     * tell {@code TYPE_MISMATCH} from {@code GENERAL}. Reading as {@code Object}
     * cannot fail as a mismatch, which leaves the type decision here, where the
     * requested type is actually known.
     */
    private <T> ProviderEvaluation<T> resolve(
            String flagKey,
            T defaultValue,
            EvaluationContext ctx,
            ResolutionMapping.TypeGuard<T> guard,
            String expectedType) {

        FeatureflipClient current = currentClient();
        if (current == null) {
            return ResolutionMapping.error(defaultValue, ErrorCode.PROVIDER_NOT_READY, NOT_READY_MESSAGE);
        }

        EvaluationDetail<Object> detail;
        try {
            detail = current.jsonVariationDetail(
                    flagKey, ContextMapping.toFeatureflipContext(ctx), null, Object.class);
        } catch (RuntimeException e) {
            return ResolutionMapping.error(defaultValue, ErrorCode.GENERAL, e.toString());
        }

        return ResolutionMapping.resolve(flagKey, defaultValue, detail, guard, expectedType);
    }

    /** Reads the client under the lifecycle lock, so an evaluation racing shutdown sees one or none. */
    private FeatureflipClient currentClient() {
        synchronized (lifecycleLock) {
            return client;
        }
    }
}
