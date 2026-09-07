plugins {
    `java-library`
    `maven-publish`
    signing
    id("net.thebugmc.gradle.sonatype-central-portal-publisher") version "1.2.4"
}

group = "io.featureflip"
version = "0.1.0"

java {
    toolchain {
        // Matches the Featureflip Java SDK's floor, and the OpenFeature Java
        // SDK's — it requires Java 11 or higher.
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Both are `api`, and deliberately: a caller of this provider necessarily
    // holds OpenFeature's types (it registers the provider and evaluates through
    // its client), and reaches Featureflip's own configuration types through the
    // constructors below. Hiding either behind `implementation` would leave the
    // published POM claiming a surface consumers cannot compile against.
    //
    // Both are consumed from Maven Central rather than as a project dependency
    // on ../java-sdk: this package is mirrored to a public repository that has no
    // sibling to reference, so a project dependency would publish a POM nobody
    // outside this monorepo can resolve.
    api("io.featureflip:featureflip-java:2.9.0")
    api("dev.openfeature:sdk:1.21.0")

    // Compile-only, and only to satisfy javac. The OpenFeature SDK's class files
    // reference `@SuppressFBWarnings`, which it declares `provided`/`optional`, so
    // without the annotation on the compile classpath javac emits a `[classfile]`
    // warning for every annotated member — and `-Werror` below turns those into a
    // failed build. Fixing the cause beats muting the whole lint category, which
    // also covers genuinely malformed class files.
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.2")
    // `compileOnly` does not extend to the test compile classpath, and the tests
    // reference the same annotated OpenFeature types.
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.10.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Drives the REAL Featureflip client over HTTP in the events test. The SDK's
    // fixed-value test client has no flag store, so it can never report a change —
    // and "initialize actually subscribes" is the one thing that, if broken, makes
    // configuration-changed events silently never fire.
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:5.5.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

centralPortal {
    username = System.getenv("MAVEN_USERNAME") ?: ""
    password = System.getenv("MAVEN_PASSWORD") ?: ""

    pom {
        name.set("Featureflip OpenFeature Provider (Java)")
        description.set("OpenFeature provider for Featureflip - a feature flag SaaS platform")
        // Project homepage, not the source repo — <scm> below is what names the
        // repository. Maven Central renders this as the "Project URL" link.
        url.set("https://featureflip.io")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                name.set("Featureflip Team")
                organization.set("Canopy Labs LLC")
                organizationUrl.set("https://featureflip.io")
            }
        }

        scm {
            url.set("https://github.com/canopy-labs/featureflip-java-openfeature")
        }
    }
}

signing {
    val signingKey = findProperty("signing.key") as String? ?: System.getenv("GPG_PRIVATE_KEY")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("GPG_PASSPHRASE")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}
