plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    // The flaky-test marker (org.krost.unidrive.testing.Flaky) lives in the test fixtures: every module's tests get
    // it from the root build script, and it stays out of the shipped jar.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // UD-255: the shared correlation-id Ktor plugin lives here so every
    // HTTP-using provider can install it without a new inter-module dep.
    // ktor-client-core brings slf4j-api transitively for the plugin's DEBUG
    // logging.
    implementation(libs.ktor.client.core)
    // The one HTTP engine of the app, named only in UnidriveHttpClient.kt (gkrost/unidrive-cli#566).
    implementation(libs.ktor.client.okhttp)

    // UD-343: shared `UnidriveJson` singleton lives here too so every
    // provider's wire-format parser can stop building its own Json {}
    // configuration block.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)

    // The provider conformance suite: the contracts each provider module runs against its own fakes.
    testFixturesImplementation(kotlin("test-junit"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":providers:onedrive"))

    // archive#644: the plugin's log lines are asserted through a logback ListAppender.
    testImplementation(libs.logback.classic)
}

tasks.test {
    useJUnit()
}
