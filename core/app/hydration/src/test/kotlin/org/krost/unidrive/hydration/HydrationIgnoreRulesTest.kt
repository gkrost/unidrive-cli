package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.ignore.IgnoreLayer
import org.krost.unidrive.sync.ignore.IgnoreSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#660: the mount's uploads follow the ignore rules (docs/dev/specs/ignore-layering-design.md). An ignored path is
 * keep-local: written, kept and listed, never uploaded. Downloads and the listing of cloud items are not touched.
 * The rule files are read from the hydration cache.
 */
class HydrationIgnoreRulesTest {
    private fun CoroutineScope.env(
        ignore: IgnoreSettings? = IgnoreSettings(),
        excludePatterns: List<String> = emptyList(),
        cacheMaxBytes: Long = HydrationImpl.DEFAULT_CACHE_MAX_BYTES,
    ) = HydrationTestEnv(recoveryUploadScope = this, ignore = ignore, excludePatterns = excludePatterns, cacheMaxBytes = cacheMaxBytes)

    /** Writes [content] to [path] through the mount (create, the client's bytes, the hand-over); the hand-over's reply. */
    private suspend fun HydrationTestEnv.write(
        path: String,
        content: String,
    ): OpenResult.Ok {
        val created = hydration.create("conn1", "h-$path", path)
        assertIs<CreateResult.Ok>(created, "create $path")
        Files.writeString(created.cachePath, content)
        val opened = hydration.openForWrite("conn1", "h-$path", path, created.cachePath)
        assertIs<OpenResult.Ok>(opened, "open_write $path")
        return opened
    }

    private suspend fun HydrationTestEnv.listed(prefix: String): Map<String, ListResult.Entry> {
        val r = hydration.list(prefix)
        assertIs<ListResult.Ok>(r)
        return r.entries.associateBy { it.path }
    }

    @Test
    fun `an ignored file is not uploaded and the cloud copy below the rule stays listed and downloadable`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertFolderEntry("/proj/build")
            env.stateDb.insertFolderEntry("/proj/src")
            env.syncEngine.seedCacheContent("/proj/.unidrive.ignore", "build/\n")
            env.stateDb.insertUnhydratedEntry("/proj/build/old.bin", 1)
            env.syncEngine.seedRemoteContent("/proj/build/old.bin", "c")

            assertTrue(env.write("/proj/build/out.bin", "local build output").excluded)
            assertFalse(env.write("/proj/src/Main.kt", "fun main() {}").excluded)
            advanceUntilIdle()

            assertNull(env.syncEngine.remoteContentSeen("/proj/build/out.bin"), "an ignored file is never uploaded")
            assertEquals("fun main() {}", env.syncEngine.remoteContentSeen("/proj/src/Main.kt"), "its neighbour is")
            val build = env.listed("/proj/build")
            assertEquals("id-/proj/build/old.bin", build.getValue("/proj/build/old.bin").remoteId, "the cloud item stays listed")
            assertTrue(build.getValue("/proj/build/old.bin").excluded, "and is marked")
            assertTrue(build.getValue("/proj/build/out.bin").excluded)
            assertTrue(env.listed("/proj").getValue("/proj/build").excluded, "the folder itself matches `build/`")
            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/proj/build/old.bin"), "downloads are not held back")
            assertEquals("c", Files.readString(env.syncEngine.resolveCachePath("/proj/build/old.bin")))
        }

    @Test
    fun `git is ignored by default and a root rule re-includes it`() =
        runTest {
            val plain = env()
            plain.stateDb.insertFolderEntry("/repo")
            plain.stateDb.insertFolderEntry("/repo/.git")
            assertTrue(plain.write("/repo/.git/config", "[core]").excluded)
            advanceUntilIdle()
            assertNull(plain.syncEngine.remoteContentSeen("/repo/.git/config"))

            val opted = env()
            opted.syncEngine.seedCacheContent("/.unidrive.ignore", "!.git\n")
            opted.stateDb.insertFolderEntry("/repo")
            opted.stateDb.insertFolderEntry("/repo/.git")
            assertFalse(opted.write("/repo/.git/config", "[core]").excluded)
            advanceUntilIdle()
            assertEquals("[core]", opted.syncEngine.remoteContentSeen("/repo/.git/config"))
        }

    @Test
    fun `a rule file written through the mount applies to the next write`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/w")
            assertFalse(env.write("/w/first.log", "uploaded").excluded)
            env.write("/w/.unidrive.ignore", "*.log\n")
            assertTrue(env.write("/w/second.log", "kept here").excluded)
            advanceUntilIdle()
            assertEquals("*.log\n", env.syncEngine.remoteContentSeen("/w/.unidrive.ignore"), "the rule file itself uploads")
            assertNull(env.syncEngine.remoteContentSeen("/w/second.log"))
        }

    @Test
    fun `gitignore files apply only with the opt-in`() =
        runTest {
            for (honor in listOf(false, true)) {
                val env = env(IgnoreSettings(honorGitignore = honor))
                env.stateDb.insertFolderEntry("/p")
                env.syncEngine.seedCacheContent("/p/.gitignore", "*.log\n")
                assertEquals(honor, env.write("/p/app.log", "log line").excluded, "honor_gitignore=$honor")
                advanceUntilIdle()
                assertEquals(if (honor) null else "log line", env.syncEngine.remoteContentSeen("/p/app.log"))
            }
        }

    @Test
    fun `an ignored local-only file is not a leftover at the next start`() =
        runTest {
            // A restart (remount, redeploy) replays the pending uploads and sweeps the cache. An ignored file written
            // through the mount must come out of both as what it is: a local-only item, kept in place, listed, never
            // uploaded and not counted as owed to the cloud.
            val env = env(cacheMaxBytes = 1)
            env.stateDb.insertFolderEntry("/proj")
            env.syncEngine.seedCacheContent("/proj/.unidrive.ignore", "node_modules/\n")
            env.stateDb.insertFolderEntry("/proj/node_modules")
            env.write("/proj/node_modules/dep.js", "module.exports = 1")
            advanceUntilIdle()

            assertEquals(0, env.hydration.replayPendingUploads(), "the start-up replay does not take it")
            env.hydration.sweepCache()
            advanceUntilIdle()

            val cache = env.syncEngine.resolveCachePath("/proj/node_modules/dep.js")
            assertEquals("module.exports = 1", Files.readString(cache), "the cache sweep keeps its only copy")
            assertNull(env.syncEngine.remoteContentSeen("/proj/node_modules/dep.js"))
            val row = env.listed("/proj/node_modules").getValue("/proj/node_modules/dep.js")
            assertTrue(row.excluded, "listed as ignored, not as an unknown local file")
            assertEquals(0, env.hydration.uploadHealth().pending, "not owed to the cloud")
        }

    @Test
    fun `a rule file only the cloud holds is downloaded on purpose and applies before it is opened`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "*")

            assertTrue(env.write("/proj/notes.txt", "draft").excluded, "the cloud's rule ignores it")
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/proj/notes.txt"))
            val ignore = assertNotNull(env.engine.uploadIgnore)
            assertTrue(ignore.heldBack().isEmpty(), "nothing waits: the rule file was read")
            val why = ignore.explain("/proj/notes.txt")
            assertEquals(IgnoreLayer.UNIDRIVE_IGNORE, why.layer)
            assertEquals("proj/.unidrive.ignore", why.rule?.source)
            assertFalse(
                Files.exists(env.syncEngine.resolveCachePath("/proj/.unidrive.ignore")),
                "read beside the cache: the rule file is not hydrated by it",
            )
        }

    @Test
    fun `a rule file under a remapped folder is fetched at that folder's remote path`() =
        runTest {
            // A child with no remote path of its own is requested at the nearest ancestor whose remote path differs,
            // and still by its own id.
            val env = env()
            env.stateDb.insertFolderEntry("/team", remotePath = "/srv-team")
            env.stateDb.insertUnhydratedEntry("/team/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/srv-team/.unidrive.ignore", "*")

            assertTrue(env.write("/team/notes.txt", "draft").excluded, "the cloud's rule ignores it")
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/team/notes.txt"))
            assertEquals(
                "id-/team/.unidrive.ignore" to "/srv-team/.unidrive.ignore",
                env.providerForTest.downloadsById.single(),
            )
            assertEquals(IgnoreLayer.UNIDRIVE_IGNORE, assertNotNull(env.engine.uploadIgnore).explain("/team/notes.txt").layer)
        }

    @Test
    fun `a rule file larger than the cap in the cloud is not downloaded and holds its folder back`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1024L * 1024L + 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "#")

            assertTrue(env.write("/proj/notes.txt", "draft").excluded, "fail closed")
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/proj/notes.txt"))
            assertEquals(0, env.syncEngine.downloadCount(), "a rule file over the cap is not downloaded")
            val held = assertNotNull(env.engine.uploadIgnore).heldBack().single()
            assertEquals("/proj/.unidrive.ignore", held.file)
            assertTrue(held.reason.startsWith("larger than 1048576 bytes in the cloud"), held.reason)
        }

    @Test
    fun `a rule file whose download fails holds its folder back until it is opened`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "*")
            env.providerForTest.makeNextDownloadThrow(java.io.IOException("offline"))

            assertTrue(env.write("/proj/notes.txt", "draft").excluded, "held back: the rules there are not known")
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/proj/notes.txt"))
            val ignore = assertNotNull(env.engine.uploadIgnore)
            val held = ignore.heldBack().single()
            assertEquals("/proj", held.dir)
            assertTrue(held.reason.contains("download failed"), held.reason)
            assertEquals(IgnoreLayer.UNREADABLE, ignore.explain("/proj/notes.txt").layer)

            // Opening the rule file hydrates it (one byte, "*": everything there is ignored), which releases the
            // folder; the held-back file is now decided by the rule.
            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/proj/.unidrive.ignore"))
            assertTrue(ignore.heldBack().isEmpty())
            assertEquals(IgnoreLayer.UNIDRIVE_IGNORE, ignore.explain("/proj/notes.txt").layer)
        }

    @Test
    fun `a paused daemon downloads no rule file and holds its folder back until the pause ends`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "*")
            env.pause.pause("user", auto = false)

            assertTrue(env.write("/proj/notes.txt", "draft").excluded, "held back: the rules there are not known")
            assertEquals(0, env.syncEngine.downloadCount(), "nothing is downloaded while paused")
            val ignore = assertNotNull(env.engine.uploadIgnore)
            val held = ignore.heldBack().single()
            assertEquals("/proj", held.dir)
            assertTrue(held.reason.contains("paused"), held.reason)

            // The end of the pause has the rule files probed again: the next question downloads it and releases the folder.
            env.pause.resume()
            assertTrue(ignore.heldBack().isEmpty(), "released once the rule file is read")
            assertEquals(1, env.syncEngine.downloadCount())
            assertEquals(IgnoreLayer.UNIDRIVE_IGNORE, ignore.explain("/proj/notes.txt").layer)
        }

    @Test
    fun `a held-back file uploads at the next start once its folder is released`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertUnhydratedEntry("/proj/.unidrive.ignore", 1)
            env.syncEngine.seedRemoteContent("/proj/.unidrive.ignore", "#")
            env.providerForTest.makeNextDownloadThrow(java.io.IOException("offline"))
            assertTrue(env.write("/proj/notes.txt", "draft").excluded)
            advanceUntilIdle()

            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/proj/.unidrive.ignore"))
            assertEquals(1, env.hydration.replayPendingUploads())
            advanceUntilIdle()
            assertEquals("draft", env.syncEngine.remoteContentSeen("/proj/notes.txt"), "holding back only delayed it")
        }

    @Test
    fun `an edit of a synced file made while it is ignored is kept and uploads once it is no longer ignored`() =
        runTest {
            val env = env()
            env.stateDb.insertFolderEntry("/proj")
            env.stateDb.insertFolderEntry("/proj/build")
            env.write("/proj/.unidrive.ignore", "build/\n")
            advanceUntilIdle()
            env.syncEngine.seedCacheContent("/proj/build/report.txt", "v1")
            val cache = env.syncEngine.resolveCachePath("/proj/build/report.txt")
            env.stateDb.insertUploadedRow("/proj/build/report.txt", Files.getLastModifiedTime(cache).toMillis(), 2)
            val baseline = env.stateDb.localMtimeOf("/proj/build/report.txt")

            Files.writeString(cache, "v2, edited")
            Files.setLastModifiedTime(cache, java.nio.file.attribute.FileTime.fromMillis(baseline!! + 60_000))
            val opened = env.hydration.openForWrite("conn1", "h1", "/proj/build/report.txt", cache)
            assertIs<OpenResult.Ok>(opened)
            assertTrue(opened.excluded)
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/proj/build/report.txt"))
            assertEquals(baseline, env.stateDb.localMtimeOf("/proj/build/report.txt"), "the cloud's version stays the baseline")
            assertEquals(CacheDisposition.PROTECTED, env.engine.mount.cacheDisposition("/proj/build/report.txt"), "never evicted")

            val rules = env.syncEngine.resolveCachePath("/proj/.unidrive.ignore")
            Files.writeString(rules, "# nothing ignored here any more\n")
            assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", "h2", "/proj/.unidrive.ignore", rules))
            advanceUntilIdle()
            assertEquals(1, env.hydration.replayPendingUploads(), "the next start sends the edit")
            advanceUntilIdle()
            assertEquals("v2, edited", env.syncEngine.remoteContentSeen("/proj/build/report.txt"))
        }

    @Test
    fun `exclude_patterns stay plain patterns beside the ignore rules and no rule file overrides them`() =
        runTest {
            val env = env(excludePatterns = listOf("/Videos/**", "*.tmp"))
            env.syncEngine.seedCacheContent("/.unidrive.ignore", "!Videos\n!Videos/**\n!*.tmp\n")
            env.stateDb.insertFolderEntry("/Videos")
            env.stateDb.insertFolderEntry("/docs")

            assertTrue(env.write("/Videos/clip.mp4", "frames").excluded)
            assertTrue(env.write("/docs/scratch.tmp", "scratch").excluded)
            advanceUntilIdle()
            assertNull(env.syncEngine.remoteContentSeen("/Videos/clip.mp4"))
            assertNull(env.syncEngine.remoteContentSeen("/docs/scratch.tmp"))
            assertEquals("/Videos/**", env.engine.mount.matchingExcludePattern("/Videos/clip.mp4"))
            assertFalse(assertNotNull(env.engine.uploadIgnore).isIgnored("/Videos/clip.mp4", false), "the rules re-include it")
        }

    @Test
    fun `with the ignore rules off the built-in defaults stay plain excludes`() =
        runTest {
            val env = env(ignore = null)
            env.syncEngine.seedCacheContent("/.unidrive.ignore", "!.git\n")
            env.stateDb.insertFolderEntry("/repo")
            env.stateDb.insertFolderEntry("/repo/.git")
            assertNull(env.engine.uploadIgnore)
            assertTrue(env.write("/repo/.git/config", "[core]").excluded, "no rule file is read")
        }
}
