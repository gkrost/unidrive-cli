plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)
    // gkrost/unidrive-cli#225: MDCContext, so the long-poll keeps the log context of the daemon that asked for hints.
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The SPI's conformance contracts, run against this provider's own fake.
    testImplementation(testFixtures(project(":app:core")))
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnit()
}
