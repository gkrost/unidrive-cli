package org.krost.unidrive.hydration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.engine.FileEvents
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HydrationFileEventsTest {
    private val logger = LoggerFactory.getLogger(FileEvents.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var savedLevel: Level? = null

    @BeforeTest
    fun attach() {
        savedLevel = logger.level
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = savedLevel
    }

    private fun events(phase: String) = appender.list.map { it.formattedMessage }
        .filter { it.startsWith("file_event phase=$phase ") }

    @Test
    fun `cold and warm hydration count one download and two serves then one eviction`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
        val path = "/a b.txt"
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 7)
        env.providerForTest.seedContent(path, "payload")
        val mount = MountEngine.over(env.engine)
        mount.ensureHydratedReporting(path)
        mount.ensureHydratedReporting(path)
        assertEquals(1, events("download_done").size)
        assertTrue(events("download_done").single().contains("bytes=7"))
        assertEquals(1, events("cache_store").size)
        assertEquals(2, events("hydrate_served").size)
        assertTrue(events("hydrate_served")[0].contains("cache_hit=false"))
        assertTrue(events("hydrate_served")[1].contains("cache_hit=true"))
        assertEquals(7L, mount.evictCacheCopy(path))
        mount.evictCacheCopy(path)
        assertEquals(1, events("cache_evict").size)
        assertTrue(events("cache_evict").single().contains("bytes=7"))
        assertTrue(appender.list.any { "download_done_count=1 download_done_bytes=7" in it.formattedMessage })
    }

    @Test
    fun `failed download emits no success or cache store and omits exception text`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
        env.stateDb.insertUnhydratedEntry("/broken", remoteSize = 7)
        env.providerForTest.makeNextDownloadThrow(IOException("private-message"))
        assertFailsWith<IOException> { MountEngine.over(env.engine).ensureHydratedReporting("/broken") }
        assertEquals(1, events("download_failed").size)
        assertEquals(1, events("hydrate_failed").size)
        assertTrue(events("download_done").isEmpty())
        assertTrue(events("cache_store").isEmpty())
        assertFalse(appender.list.any { "private-message" in it.formattedMessage })
        env.providerForTest.seedContent("/broken", "payload")
        MountEngine.over(env.engine).ensureHydratedReporting("/broken")
        assertTrue(events("download_done").single().contains("retries=1"))
    }

    @Test
    fun `explicit dehydrate logs only bytes actually removed`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
        env.stateDb.insertUnhydratedEntry("/a", remoteSize = 7)
        env.providerForTest.seedContent("/a", "payload")
        MountEngine.over(env.engine).ensureHydratedReporting("/a")
        env.hydration.dehydrate("/a")
        env.hydration.dehydrate("/a")
        assertEquals(1, events("cache_evict").size)
        assertTrue(events("cache_evict").single().contains("reason=dehydrate"))
    }

    @Test
    fun `mount upload retry reports failed attempt and successful bytes separately`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
        val path = "/changed"
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 1)
        val cache = env.syncEngine.resolveCachePath(path)
        Files.writeString(cache, "changed payload")
        val mount = MountEngine.over(env.engine)
        env.providerForTest.uploadFailuresRemaining.set(1)
        assertFailsWith<Exception> { mount.uploadFromCache(path, cache) }
        mount.uploadFromCache(path, cache)
        assertEquals(1, events("upload_failed").size)
        assertEquals(1, events("upload_done").size)
        assertTrue(events("upload_done").single().contains("bytes=15"))
        assertTrue(events("upload_done").single().contains("retries=1"))
        assertEquals(1, events("upload_confirmed").size)
    }
}
