plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)

    // The age-v1 content strategy (AgeV1Strategy): standard age-encryption.org/v1 files via kage. The bcprov
    // pin overrides kage 0.8.0's transitive 1.86 — 1.87 fixes CVEs and carries upstream's ChaCha20-Poly1305
    // speedups (~4x on the CCTV stream corpus), and the strategy's throughput depends on it.
    implementation(libs.kage)
    implementation(libs.bouncycastle.bcprov) { version { strictly("1.87") } }

    // The Tink content strategy (prototype): AES-GCM-HKDF streaming, and the AES-SIV daead the encrypted-name
    // layer would build on. Transitive gson/protobuf enter the jar; the size delta is measured in the spike.
    implementation(libs.tink)

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
