package org.krost.unidrive.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.krost.unidrive.ViewKind
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The views of a profile: stable keys, a configurable root name that yields to a live top-level item, and the one
 * question the read-only rule rests on (which paths are in a view).
 */
class ViewNamespacesTest {
    private fun views(
        vararg specs: ViewSpec,
        known: () -> Collection<String> = { emptyList() },
        ignoreCase: Boolean = false,
        log: Logger = LoggerFactory.getLogger("view-namespaces-test") as Logger,
    ) = ViewNamespaces(specs.toList(), known, ignoreCase, log)

    private fun recents(name: String? = null) = ViewSpec(ViewKind.RECENTS, name, limit = 10)

    private fun warnings(
        logger: Logger,
        block: () -> Unit,
    ): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `a question asked while the roots are being worked out waits for them and is never answered from the empty map`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val v =
            views(recents(), known = {
                reading.countDown()
                release.await(5, TimeUnit.SECONDS)
                emptyList()
            })
        val first = thread { v.kindOf("/Recents") }
        assertTrue(reading.await(5, TimeUnit.SECONDS))

        var answer: ViewKind? = null
        val second = thread { answer = v.kindOf("/Recents/x.txt") }
        second.join(200)
        assertTrue(second.isAlive, "the second question waits for the roots instead of reading an unresolved map")
        release.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals(ViewKind.RECENTS, answer, "a path in a view is a view path from the very first question")
    }

    @Test
    fun `a change that arrives while the roots are read is seen by the next question, and only by one`() {
        val reads = AtomicInteger(0)
        lateinit var v: ViewNamespaces
        v =
            views(recents(), known = {
                if (reads.incrementAndGet() == 1) v.invalidate()
                emptyList()
            })

        v.rootPath(ViewKind.RECENTS)
        assertEquals(1, reads.get())
        v.rootPath(ViewKind.RECENTS)
        assertEquals(2, reads.get(), "the invalidation that arrived mid-read is not lost")
        v.rootPath(ViewKind.RECENTS)
        assertEquals(2, reads.get(), "and it is worked out once, not at every question")
    }

    @Test
    fun `a kind has a stable key and a default name, and the root exists whatever is in it`() {
        val v = views(recents(), ViewSpec(ViewKind.FAVORITES, limit = 5), ViewSpec(ViewKind.SHARED_BY_ME, limit = 5))

        assertEquals("unidrive:view:recents", ViewNamespaces.rootKey(ViewKind.RECENTS))
        assertEquals("unidrive:view:favorites", ViewNamespaces.rootKey(ViewKind.FAVORITES))
        assertEquals("unidrive:view:shared", ViewNamespaces.rootKey(ViewKind.SHARED_BY_ME))
        assertEquals("unidrive:view:shared_with_me", ViewNamespaces.rootKey(ViewKind.SHARED_WITH_ME))
        assertEquals("unidrive:view:recents:abc", ViewNamespaces.entryKey(ViewKind.RECENTS, "abc"))
        assertEquals(listOf(ViewKind.RECENTS, ViewKind.FAVORITES, ViewKind.SHARED_BY_ME), v.kinds())
        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS))
        assertEquals("/Favorites", v.rootPath(ViewKind.FAVORITES))
        assertEquals("/Shared", v.rootPath(ViewKind.SHARED_BY_ME))
        assertNull(v.rootPath(ViewKind.SHARED_WITH_ME), "a kind the profile does not show has no root")
        for (kind in ViewKind.entries) assertEquals(kind, ViewNamespaces.kindOfWireName(ViewNamespaces.wireName(kind)))
    }

    @Test
    fun `the configured name is the root's name, surrounding blanks and composition are not part of it`() {
        val v = views(recents("  Zuletzt geo\u0308ffnet "))

        assertEquals("Zuletzt ge\u00F6ffnet", v.configuredName(ViewKind.RECENTS))
        assertEquals("/Zuletzt ge\u00F6ffnet", v.rootPath(ViewKind.RECENTS))
    }

    @Test
    fun `a name that is no folder name is not used, said once`() {
        val log = LoggerFactory.getLogger("view-name-test") as Logger
        var v: ViewNamespaces? = null
        val shown = warnings(log) { v = views(recents("a/b"), log = log) }

        assertEquals(1, shown.size, "said once: $shown")
        assertEquals("/Recents", v!!.rootPath(ViewKind.RECENTS))
        for (bad in listOf("", "  ", ".", "..", "a\\b", "a:b", "x\"y", "trail.", "tab\there", "n".repeat(101))) {
            assertEquals("Recents", views(recents(bad)).configuredName(ViewKind.RECENTS), "[$bad] falls back")
        }
    }

    @Test
    fun `a live top-level item with the name keeps it and the root takes the next free one, said once`() {
        val log = LoggerFactory.getLogger("view-collision-test") as Logger
        val top = mutableListOf("Recents")
        val v = views(recents(), known = { top }, log = log)

        val shown =
            warnings(log) {
                assertEquals("/Recents (2)", v.rootPath(ViewKind.RECENTS))
                v.invalidate()
                assertEquals("/Recents (2)", v.rootPath(ViewKind.RECENTS))
            }

        assertEquals(1, shown.size, "once, not on every question: $shown")
        assertTrue("Recents (2)" in shown.single(), shown.single())
        top += "Recents (2)"
        v.invalidate()
        assertEquals("/Recents (3)", v.rootPath(ViewKind.RECENTS), "two collisions take the third name")
        assertEquals("Recents", v.configuredName(ViewKind.RECENTS), "the name the profile asks for does not change")
    }

    @Test
    fun `where the file system folds case a name that differs by case is a collision`() {
        assertEquals("/Recents (2)", views(recents(), known = { listOf("recents") }, ignoreCase = true).rootPath(ViewKind.RECENTS))
        assertEquals("/Recents", views(recents(), known = { listOf("recents") }, ignoreCase = false).rootPath(ViewKind.RECENTS))
    }

    @Test
    fun `two views never share a name, the earlier kind keeps it`() {
        val v = views(recents("Same"), ViewSpec(ViewKind.FAVORITES, "Same", limit = 5), ViewSpec(ViewKind.SHARED_BY_ME, "same", limit = 5), ignoreCase = true)

        assertEquals("/Same", v.rootPath(ViewKind.RECENTS))
        assertEquals("/Same (2)", v.rootPath(ViewKind.FAVORITES))
        assertEquals("/same (3)", v.rootPath(ViewKind.SHARED_BY_ME))
        assertEquals(listOf("/Same", "/Same (2)", "/same (3)"), v.rootPaths().map { it.second })
    }

    @Test
    fun `the root of the trash is a top-level item like any other, the view yields to it`() {
        val v = views(recents("Papierkorb"), known = { listOf("Papierkorb", "Docs") })

        assertEquals("/Papierkorb (2)", v.rootPath(ViewKind.RECENTS))
    }

    @Test
    fun `an item that appears later moves the root aside, and one that goes lets it back`() {
        val top = mutableListOf<String>()
        val v = views(recents(), known = { top })

        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS))
        assertTrue(v.contains("/Recents/a.txt"))

        top += "Recents"
        // Not seen until the engine says the top level may have changed: the roots are worked out when asked, not on a timer.
        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS))
        v.invalidate()

        assertEquals("/Recents (2)", v.rootPath(ViewKind.RECENTS))
        assertFalse(v.contains("/Recents/a.txt"), "the live folder owns /Recents now: writes there are real writes")
        assertTrue(v.contains("/Recents (2)/a.txt"))

        top.clear()
        v.invalidate()
        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS))
    }

    @Test
    fun `a reply that must not wait for state db gets the root as last worked out, and reads nothing`() {
        val asked = AtomicInteger()
        val top = mutableListOf<String>()
        val v = views(recents(), known = { asked.incrementAndGet(); top })

        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS, fresh = false), "the configured name before anything was worked out")
        top += "Recents"
        v.invalidate()
        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS, fresh = false))
        assertEquals(0, asked.get(), "the top level was not read")

        assertEquals("/Recents (2)", v.rootPath(ViewKind.RECENTS), "a question that needs the truth reads it")
        assertEquals("/Recents (2)", v.rootPath(ViewKind.RECENTS, fresh = false))
        assertEquals(1, asked.get())
        assertNull(views().rootPath(ViewKind.RECENTS, fresh = false))
    }

    @Test
    fun `the top level is read once per change, not once per question`() {
        val asked = AtomicInteger()
        val v = views(recents(), known = { asked.incrementAndGet(); emptyList() })

        repeat(50) { v.contains("/Recents/x$it"); v.rootPath(ViewKind.RECENTS) }
        assertEquals(1, asked.get())
        v.invalidate()
        v.contains("/x")
        assertEquals(2, asked.get())
    }

    @Test
    fun `a path is in a view when it is the root or lies below it, on a segment boundary`() {
        val v = views(recents(), ViewSpec(ViewKind.FAVORITES, limit = 5))

        assertTrue(v.contains("/Recents"))
        assertTrue(v.contains("/Recents/"))
        assertTrue(v.contains("/Recents/a.txt"))
        assertTrue(v.contains("/Recents/sub/deeper/a.txt"))
        assertTrue(v.contains("/Favorites/a.txt"))
        assertFalse(v.contains("/Recent"))
        assertFalse(v.contains("/Recents2"))
        assertFalse(v.contains("/Recents (2)/a.txt"))
        assertFalse(v.contains("/Other/Recents/a.txt"))
        assertFalse(v.contains("/"))
        assertFalse(v.contains(""))
        assertTrue(v.isRoot("/Recents"))
        assertTrue(v.isRoot("/Favorites/"))
        assertFalse(v.isRoot("/Recents/a.txt"))
        assertEquals(ViewKind.FAVORITES, v.kindOf("/Favorites/x"))
        assertNull(v.kindOf("/Docs/x"))
        assertEquals(ViewLocation(ViewKind.RECENTS, "/Recents", "sub/a.txt"), v.locate("/Recents/sub/a.txt"))
    }

    @Test
    fun `a name that differs by case is the view only where the file system folds case`() {
        assertTrue(views(recents(), ignoreCase = true).contains("/RECENTS/a.txt"))
        assertFalse(views(recents(), ignoreCase = false).contains("/RECENTS/a.txt"))
    }

    @Test
    fun `a decomposed spelling of the root is the same root`() {
        val v = views(recents("Zuletzt ge\u00F6ffnet"))

        assertTrue(v.contains("/Zuletzt ge\u00F6ffnet/a.txt"))
        assertTrue(v.contains("/Zuletzt geo\u0308ffnet/a.txt"), "a client may send the name decomposed")
        assertTrue(v.isRoot("/Zuletzt geo\u0308ffnet"))
    }

    @Test
    fun `with no view nothing is in one and the top level is never read`() {
        val asked = AtomicInteger()
        val v = views(known = { asked.incrementAndGet(); emptyList() })

        assertTrue(v.isEmpty())
        assertFalse(v.contains("/Recents/a.txt"))
        assertFalse(v.isRoot("/Recents"))
        assertNull(v.kindOf("/Recents"))
        assertNull(v.rootPath(ViewKind.RECENTS))
        assertEquals(emptyList(), v.rootPaths())
        assertEquals(0, asked.get())
        assertTrue(ViewNamespaces.NONE.isEmpty())
    }

    @Test
    fun `the keys of the roots and of their entries are views' keys, a provider id is not`() {
        val v = views(recents())

        assertTrue(v.holdsId(ViewNamespaces.rootKey(ViewKind.RECENTS)))
        assertTrue(v.holdsId(ViewNamespaces.entryKey(ViewKind.FAVORITES, "abc")))
        assertFalse(v.holdsId("0f1e2d3c-aaaa-bbbb-cccc-1234567890ab"))
        assertFalse(v.holdsId(TrashNamespace.ROOT_ID))
        assertFalse(v.holdsId(null))
    }

    @Test
    fun `a failure reading the top level does not leave the roots half worked out`() {
        var fail = true
        val v = views(recents(), known = { if (fail) error("db is busy") else emptyList() })

        kotlin.runCatching { v.rootPath(ViewKind.RECENTS) }
        fail = false

        assertEquals("/Recents", v.rootPath(ViewKind.RECENTS), "the next question works them out again")
    }

    @Test
    fun `a view fetches at least one reference`() {
        kotlin.runCatching { ViewSpec(ViewKind.RECENTS, limit = 0) }.let { assertTrue(it.isFailure) }
    }
}
