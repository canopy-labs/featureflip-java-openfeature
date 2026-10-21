package io.featureflip.openfeature;

import dev.openfeature.sdk.ImmutableContext;
import io.featureflip.client.FeatureFlagConfig;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests made through a provider that owns its client name it after the SDK
 * (#4150, contract in #4138): {@code featureflip-java/<v> featureflip-openfeature-java/<v>},
 * so OpenFeature usage can be counted apart from direct SDK use. Asserted on the wire.
 */
class IdentityTest {
    private MockWebServer server;
    private FeatureflipProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) provider.shutdown();
        server.close();
    }

    private FeatureFlagConfig.Builder base() {
        return FeatureFlagConfig.builder()
                .baseUrl(server.url("").toString())
                .streaming(false)
                .pollInterval(Duration.ofSeconds(60))
                .initTimeout(Duration.ofSeconds(5));
    }

    private String firstUserAgent(FeatureFlagConfig config) throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("{\"environment\":\"e\",\"version\":1,\"flags\":[],\"segments\":[]}")
                .build());
        provider = new FeatureflipProvider("sdk-" + UUID.randomUUID(), config);
        provider.initialize(new ImmutableContext());
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        return request.getHeaders().get("User-Agent");
    }

    @Test
    void anOwnedClientSendsTheProviderTokenAfterTheSdkToken() throws Exception {
        assertThat(firstUserAgent(base().build()))
                .matches("featureflip-java/\\S+ featureflip-openfeature-java/" + FeatureflipProvider.VERSION.replace(".", "\\."));
    }

    @Test
    void aWrapperTheCallerAlreadySetIsKept() throws Exception {
        // The SDK carries one wrapper token, and the caller's is the more specific.
        assertThat(firstUserAgent(base().wrapper("acme-platform", "3.1").build()))
                .endsWith(" acme-platform/3.1");
    }

    @Test
    void theCopyCarriesEverySettingOfTheCallersConfig() throws Exception {
        // FeatureFlagConfig has no toBuilder(), so the provider rebuilds it field by
        // field. Every public getter except the wrapper's must survive the copy, so a
        // setting the SDK adds later fails here instead of being dropped silently.
        FeatureFlagConfig original = base()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(7))
                .flushInterval(Duration.ofSeconds(11))
                .flushBatchSize(42)
                .build();

        FeatureFlagConfig copy = FeatureflipProvider.identified(original);

        for (Method getter : FeatureFlagConfig.class.getMethods()) {
            String name = getter.getName();
            if (getter.getParameterCount() != 0 || getter.getDeclaringClass() != FeatureFlagConfig.class) continue;
            if (!(name.startsWith("get") || name.startsWith("is")) || name.startsWith("getWrapper")) continue;
            assertThat(getter.invoke(copy)).as(name).isEqualTo(getter.invoke(original));
        }
        assertThat(copy.getWrapperName()).isEqualTo("featureflip-openfeature-java");
        assertThat(original.getWrapperName()).isNull();
    }
}
