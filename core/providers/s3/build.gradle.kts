plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers)
}

// The provider against a real S3 server (MinIO in a container): needs a Docker engine and pulls an image, so it stays
// out of `test` and with it out of `check`. `./gradlew :providers:s3:minioTest` runs it; without Docker it skips.
val minioSuite = "org.krost.unidrive.s3.S3MinioContainerTest"

tasks.test {
    useJUnit()
    filter { excludeTestsMatching(minioSuite) }
}

tasks.register<Test>("minioTest") {
    group = "verification"
    description = "Runs the S3 provider against MinIO in a container. Not part of check; skips without a Docker engine."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter { includeTestsMatching(minioSuite) }
    // A run against a server has nothing to be up to date with.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
