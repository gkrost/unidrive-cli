package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * archive#1076: places that name the same thing in three languages (Kotlin, Gradle Kotlin DSL, shell) drifted apart once; this pins
 * what they have to agree on. Static text checks of files in the repository, the way InstallShTest finds `dist/`.
 */
class BuildAndDistAgreeTest {
    private fun repoRoot(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            if (Files.exists(d.resolve("dist/install.sh"))) return d
            d = d.parent
        }
        error("dist/install.sh not found above ${System.getProperty("user.dir")}")
    }

    private fun text(relative: String): String = Files.readString(repoRoot().resolve(relative))

    @Test
    fun the_probe_jar_the_lookup_wants_is_the_one_the_build_stages_and_install_sh_copies() {
        val prefix = CoroutineDebug.AGENT_JAR_PREFIX
        val gradle = text("core/app/cli/build.gradle.kts")
        val named = Regex("""startsWith\("(kotlinx-coroutines-[a-z-]+)"\)""").findAll(gradle).map { it.groupValues[1] }.toList()
        assertTrue(named.isNotEmpty(), "the build names the probe jar it stages")
        assertEquals(setOf(prefix), named.toSet(), "every place in build.gradle.kts that picks the probe jar uses the lookup's prefix")
        assertTrue("$prefix*.jar" in text("dist/install.sh"), "install.sh globs for the same jar")
    }

    @Test
    fun the_gradle_deploy_installs_the_units_of_dist_and_writes_none_of_its_own() {
        val gradle = text("core/app/cli/build.gradle.kts")
        assertFalse("[Service]" in gradle, "an inline unit in the build would drift from dist/ again")
        assertFalse("ExecStart=" in gradle)
        for (unit in listOf("unidrive.service", "unidrive@.service", "unidrive-mount@.service")) {
            assertTrue("\"$unit\"" in gradle, "deployLinux copies $unit")
            assertTrue(Files.exists(repoRoot().resolve("dist/$unit")), "dist/$unit exists")
        }
    }
}
