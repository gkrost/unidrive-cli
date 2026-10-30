plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    // The engine test fakes (FakeCloudProvider) are shared with the mount front-end's tests in
    // :app:hydration, which build their SyncEngine host on them.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:core"))
    // The state repository, path and scope rules, and the remote-operation guards
    // shared with the mount operations (archive#560 U2).
    implementation(project(":app:engine-core"))

    implementation(libs.kotlinx.coroutines.core)
    // gkrost/unidrive-cli#225: MDCContext, so a daemon's scan id and a rule-file download keep the daemon's log context.
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    implementation(libs.logback.classic)

    // FakeCloudProvider implements the provider SPI and suspends (delay).
    testFixturesApi(project(":app:core"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
