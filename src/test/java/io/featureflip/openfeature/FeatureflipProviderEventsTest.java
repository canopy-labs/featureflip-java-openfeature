package io.featureflip.openfeature;

import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvent;
import io.featureflip.client.FeatureFlagConfig;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PROVIDER_CONFIGURATION_CHANGED}, end to end.
 *
 * <p>Driven through a real client polling a real HTTP server rather than through
 * the provider's listener method, deliberately. The interesting failure is not
 * "does the event get built correctly" — that is one line — it is
 * <em>"does {@code initialize} actually subscribe"</em>, which nothing observable
 * distinguishes from a provider that simply never sees a change. The SDK's
 * fixed-value test client has no flag store behind it and can never report one,
 * so proving the wiring needs a client with a real store.
 */
class FeatureflipProviderEventsTest {

    private static final String FLAG_TEMPLATE = "{\"environment\":\"test\",\"version\":%d,\"flags\":["
            + "{\"key\":\"gate\",\"version\":%d,\"type\":\"Boolean\",\"enabled\":%s,"
            + "\"variations\":[{\"key\":\"on\",\"value\":true},{\"key\":\"off\",\"value\":false}],"
            + "\"rules\":[],\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"on\"},"
            + "\"offVariation\":\"off\",\"prerequisites\":[]}],\"segments\":[]}";

    private MockWebServer server;
    private final AtomicBoolean flagEdited = new AtomicBoolean(false);
    private OpenFeatureAPI api;
    private String domain;
    private FeatureflipProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        // A dispatcher rather than enqueued responses: the poller keeps asking, and a
        // queue that runs dry would make the test's timing its failure mode.
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = flagEdited.get()
                        ? String.format(FLAG_TEMPLATE, 2, 2, "false")
                        : String.format(FLAG_TEMPLATE, 1, 1, "true");
                return new MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(body)
                        .build();
            }
        });
        server.start();
        api = OpenFeatureAPI.getInstance();
        // Domain-scoped, so this never touches the default provider another test in
        // the same JVM may be relying on.
        domain = "featureflip-events-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.shutdown();
        }
        server.close();
    }

    @Test
    void aFlagEditReachesTheOpenFeatureClientAsAConfigurationChange() throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        AtomicReference<List<String>> reported = new AtomicReference<>();

        FeatureFlagConfig config = FeatureFlagConfig.builder()
                .baseUrl(server.url("").toString())
                .streaming(false)
                .pollInterval(Duration.ofMillis(200))
                .initTimeout(Duration.ofSeconds(5))
                .build();

        // A unique key per run: the SDK shares one core per key across handles, so a
        // fixed key would let a previous test's client answer this one.
        provider = new FeatureflipProvider("sdk-" + UUID.randomUUID(), config);
        api.setProviderAndWait(domain, provider);
        api.getClient(domain).on(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, (EventDetails details) -> {
            reported.set(details.getFlagsChanged());
            changed.countDown();
        });

        flagEdited.set(true);

        assertThat(changed.await(10, TimeUnit.SECONDS))
                .as("editing a flag must reach the OpenFeature client as PROVIDER_CONFIGURATION_CHANGED")
                .isTrue();
        assertThat(reported.get()).containsExactly("gate");
    }

    @Test
    void theInitialFlagLoadIsNotReportedAsAChange() throws Exception {
        // A cold start is not a change; OpenFeature signals that with PROVIDER_READY.
        // Without this, every provider registration would wake every handler with the
        // whole catalogue.
        CountDownLatch changed = new CountDownLatch(1);

        FeatureFlagConfig config = FeatureFlagConfig.builder()
                .baseUrl(server.url("").toString())
                .streaming(false)
                .pollInterval(Duration.ofMillis(200))
                .initTimeout(Duration.ofSeconds(5))
                .build();

        provider = new FeatureflipProvider("sdk-" + UUID.randomUUID(), config);
        api.setProviderAndWait(domain, provider);
        api.getClient(domain).on(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, details -> changed.countDown());

        // Long enough for several polls of the unchanged snapshot.
        assertThat(changed.await(2, TimeUnit.SECONDS))
                .as("an unchanged snapshot, re-polled, must not be reported as a change")
                .isFalse();
    }
}
