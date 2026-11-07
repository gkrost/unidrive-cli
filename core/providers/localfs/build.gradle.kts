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
}

tasks.test {
    useJUnit()
}
