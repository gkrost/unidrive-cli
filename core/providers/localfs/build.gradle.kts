plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The SPI's conformance contracts, run against this provider's own fake.
    testImplementation(testFixtures(project(":app:core")))
}

tasks.test {
    useJUnit()
}
