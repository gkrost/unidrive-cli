plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The mount rig's IPC replies are JSON, as HydrationIpcHandler answers them.
    testImplementation(libs.kotlinx.serialization.json)
    // The engines the decorator must satisfy: the mount rig's row and size contracts (confirmedDownloadSize, the
    // warm-cache match, the incomplete-hydration guard) are what a listing's size feeds. Test scope only; the
    // module itself stays on :app:core (the CLI does the wiring, as with every provider).
    testImplementation(project(":app:engine-core"))
    testImplementation(project(":app:sync"))
    testImplementation(testFixtures(project(":app:sync")))
    testImplementation(project(":app:hydration"))
}

tasks.test {
    useJUnit()
}
