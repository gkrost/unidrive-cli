package org.krost.unidrive.hydration

import org.krost.unidrive.io.stagingSiblingName
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#508: the stall watchdog, the pacer and the progress meter read a download's bytes from its staging
 * files. The providers' own staging name carries a random part (writeStagedDownload, windows#454), so it is found by the
 * mount's name as a prefix, and the mount's name is short enough that the provider never falls back to a name without it.
 */
class HydrationStagingTest {
    private val dir: Path = createTempDirectory("hydration-staging")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun the_bytes_of_a_provider_staging_file_with_a_random_part_are_measured() {
        val staged = dir.resolve(HydrationStaging.stagingName("report.pdf"))
        // As writeStagedDownload names it: the mount's name, `.<uuid>`, `.unidrive-tmp`.
        dir.resolve(stagingSiblingName(staged.fileName.toString(), ".${UUID.randomUUID()}.unidrive-tmp")).writeBytes(ByteArray(5))
        assertEquals(5L, HydrationStaging.stagedBytes(staged))
    }

    @Test
    fun the_bytes_of_a_provider_staging_file_without_a_random_part_are_measured() {
        val staged = dir.resolve(HydrationStaging.stagingName("report.pdf"))
        // As the Internxt download names it.
        dir.resolve(staged.fileName.toString() + ".unidrive-tmp").writeBytes(ByteArray(7))
        assertEquals(7L, HydrationStaging.stagedBytes(staged))
    }

    @Test
    fun another_downloads_staging_file_in_the_same_folder_is_not_counted() {
        val staged = dir.resolve(HydrationStaging.stagingName("report.pdf"))
        val other = dir.resolve(HydrationStaging.stagingName("report.pdf"))
        dir.resolve(other.fileName.toString() + ".${UUID.randomUUID()}.unidrive-tmp").writeBytes(ByteArray(9))
        assertEquals(0L, HydrationStaging.stagedBytes(staged))
    }

    @Test
    fun a_long_cache_name_leaves_the_provider_room_for_its_staging_suffix() {
        // 200 bytes: `name.hydrating-<uuid>` fits in 255, but the provider's `.<uuid>.unidrive-tmp` on top would not.
        val longName = "a".repeat(196) + ".bin"
        val staged = dir.resolve(HydrationStaging.stagingName(longName))
        val providers = stagingSiblingName(staged.fileName.toString(), ".${UUID.randomUUID()}.unidrive-tmp")
        assertTrue(providers.startsWith(staged.fileName.toString()), "the provider keeps the mount's name: $providers")
        dir.resolve(providers).writeBytes(ByteArray(3))
        assertEquals(3L, HydrationStaging.stagedBytes(staged))
    }

    @Test
    fun a_short_cache_name_keeps_its_name_in_the_staging_name() {
        val name = HydrationStaging.stagingName("report.pdf")
        assertTrue(name.startsWith("report.pdf.hydrating-"), name)
        assertTrue(Files.notExists(dir.resolve(name)))
    }
}
