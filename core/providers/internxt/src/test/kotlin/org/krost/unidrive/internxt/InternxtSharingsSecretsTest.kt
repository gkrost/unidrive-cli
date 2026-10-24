package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.krost.unidrive.internxt.model.InternxtJson
import org.krost.unidrive.internxt.model.SharedByMe
import org.krost.unidrive.internxt.model.SharedItemWire
import org.krost.unidrive.internxt.model.SharedItemsPageWire
import org.krost.unidrive.internxt.model.SharedRef
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The answers of `GET sharings/folders` and `GET sharings/files` hold, for every item, the owner's bridge credentials, the
 * sharing's `encryptionKey`, a `token`, the owner's e-mail address and avatar, and for a public link its code (for a legacy
 * link the key and the code open the account's mnemonic). A listing may read none of it. Every fixture here carries a canary
 * (see [Canary]) in each of those places, and these tests look for the canaries wherever a secret could end up: in a model, in
 * the field names of the models, in the result, in the text of a failure, and in every log line of a whole listing at DEBUG
 * and at TRACE, for the answers that succeed and for the ones that fail.
 *
 * A new field on one of these models is a decision, not a convenience: the field-name tests below fail on it until the list
 * is changed on purpose, and the field wants a canary in the fixtures.
 */
class InternxtSharingsSecretsTest {
    // ---- the models ------------------------------------------------------------------------------------------------

    private val secretWords = Regex("(?i)key|credential|token|code|mail|avatar|pass|secret|network|bridge|user|mnemonic")

    private fun wireFieldNames(
        descriptor: SerialDescriptor,
        visited: MutableSet<String> = HashSet(),
    ): Set<String> {
        if (!visited.add(descriptor.serialName)) return emptySet()
        val names = HashSet<String>()
        for (i in 0 until descriptor.elementsCount) {
            if (descriptor.kind == StructureKind.CLASS) names += descriptor.getElementName(i)
            names += wireFieldNames(descriptor.getElementDescriptor(i), visited)
        }
        return names
    }

    private fun declaredFields(type: Class<*>): Set<String> =
        type.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.mapTo(HashSet()) { it.name }

    @Test
    fun `the wire models declare exactly the fields a reference needs`() {
        assertEquals(
            setOf("folders", "files", "uuid", "plainName", "sharedWithMe", "status", "deleted", "removed"),
            wireFieldNames(SharedItemsPageWire.serializer().descriptor),
            "a new field on the wire models of the sharings answers needs a canary in the fixtures first",
        )
        assertEquals(setOf("uuid", "plainName", "sharedWithMe", "status", "deleted", "removed"), declaredFields(SharedItemWire::class.java))
        assertEquals(setOf("folders", "files"), declaredFields(SharedItemsPageWire::class.java))
    }

    @Test
    fun `the result types declare exactly the fields a reference needs`() {
        assertEquals(setOf("uuid", "isFolder", "plainName"), declaredFields(SharedRef::class.java))
        assertEquals(setOf("refs", "truncated", "foldersCut"), declaredFields(SharedByMe.Listed::class.java))
    }

    @Test
    fun `no model has a field named like a secret of the answers`() {
        val names =
            wireFieldNames(SharedItemsPageWire.serializer().descriptor) +
                listOf(SharedItemWire::class.java, SharedItemsPageWire::class.java, SharedRef::class.java, SharedByMe.Listed::class.java)
                    .flatMap { declaredFields(it) }
        val secret = names.filter { secretWords.containsMatchIn(it) }
        assertEquals(emptyList(), secret, "encryptionKey, credentials, token, encryptedCode, the owner's e-mail and avatar are not read")
    }

    @Test
    fun `what the models and the result print holds no canary, although the answer held all of them`() =
        runTest {
            val items = listOf(FakeShare("fo-link", "Public folder", publicLink = true), FakeShare("fo-theirs", "Theirs", sharedWithMe = true))
            val body = fakeSharingsPageJson(items, isFolder = true)
            assertEquals(Canary.ALL, Canary.ALL.filter { it in body }, "the fixture must carry every canary, or this test proves nothing")

            val page = InternxtJson.decodeFromString<SharedItemsPageWire>(body)
            val printed = page.toString() + checkNotNull(page.folders).joinToString("\n") { it.toString() }
            assertEquals(emptyList(), Canary.foundIn(printed))
            assertTrue("fo-link" in printed, "the models did read the answer")

            val service = internxtApiOn(FakeDrive(emptyList(), emptyList(), sharings = FakeSharings(folders = FakeSharings.pages(items))))
            val listed = service.listSharedByMe()
            assertEquals(emptyList(), Canary.foundIn(listed.toString()))
            val refs = (listed as SharedByMe.Listed).refs
            assertEquals(1, refs.size)
            assertEquals(emptyList(), Canary.foundIn(refs.joinToString("\n") { it.toString() }))
            service.close()
        }

    @Test
    fun `the printed result names the counts and no name`() {
        val listed = SharedByMe.Listed(listOf(SharedRef("fo-1", true, "Tax 2026")), truncated = true)

        assertEquals("Listed(refs=1, truncated=true, foldersCut=false)", listed.toString())
    }

    // ---- the log ---------------------------------------------------------------------------------------------------

    private fun rendered(event: ILoggingEvent): String =
        buildString {
            append(event.level).append(' ').append(event.loggerName).append(' ').append(event.formattedMessage)
            event.argumentArray?.forEach { append(' ').append(it) }
            event.throwableProxy?.let { append('\n').append(ThrowableProxyUtil.asString(it)) }
            event.mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
            event.markerList?.forEach { append(" ").append(it) }
        }

    // Every log event of the process while [block] runs, as the text of its message, arguments, throwable chain and MDC.
    private suspend fun logLinesAt(
        level: Level,
        block: suspend () -> Unit,
    ): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = root.level
        root.level = level
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            root.level = before
        }
        return appender.list.map { rendered(it) }
    }

    private val unreadableItem = """{"folders":[{"plainName":"No identity","sharedWithMe":false,"encryptionKey":"${Canary.ENCRYPTION_KEY}","token":"${Canary.TOKEN}"}],"files":[]}"""

    private val truncatedBody = """{"folders":[{"uuid":"fo-1","sharedWithMe":false,"encryptionKey":"${Canary.ENCRYPTION_KEY}","credentials":{"networkPass":"${Canary.NETWORK_PASS}"""

    // An answer that holds the secrets of the account but not the array of the route that was asked.
    private val noArrayBody = """{"files":[],"credentials":{"networkPass":"${Canary.NETWORK_PASS}"},"token":"${Canary.TOKEN}","role":"OWNER"}"""

    private fun mixedAccount(): FakeSharings {
        val folders =
            (0 until 57).map { FakeShare("fo-$it", "Folder $it", publicLink = it % 10 == 0) } +
                FakeShare("fo-theirs", "Theirs", sharedWithMe = true) + FakeShare("fo-gone", "Gone", deleted = true)
        val files = (0 until 7).map { FakeShare("fi-$it", "file$it.txt", isFolder = false, publicLink = it == 3) }
        return FakeSharings(folders = FakeSharings.pages(folders), files = FakeSharings.pages(files))
    }

    private fun scenarios(): Map<String, () -> FakeSharings> =
        linkedMapOf(
            "success" to { mixedAccount() },
            "400" to { FakeSharings(failure = { _, _ -> 400 }) },
            "404" to { FakeSharings(failure = { _, _ -> 404 }) },
            "405" to { FakeSharings(failure = { _, _ -> 405 }) },
            "503" to { FakeSharings(failure = { _, _ -> 503 }) },
            "524" to { FakeSharings(failure = { _, _ -> 524 }) },
            "401" to { FakeSharings(failure = { _, _ -> 401 }) },
            "unreadable item" to { FakeSharings(raw = { _, _ -> unreadableItem }) },
            "no JSON" to { FakeSharings(raw = { _, _ -> truncatedBody }) },
            "no array of the route" to { FakeSharings(raw = { _, _ -> noArrayBody }) },
            "an error object without arrays" to { FakeSharings(raw = { _, _ -> fakeHostileErrorBody(200) }) },
            "404 on a later page" to { FakeSharings(folders = FakeSharings.pages((0 until 60).map { FakeShare("fo-$it", "F$it") }), failure = { _, page -> if (page == 1) 404 else null }) },
        )

    private val expectedOutcomes =
        mapOf(
            "success" to "Listed",
            "400" to "InternxtApiException",
            "404" to "Unsupported",
            "405" to "Unsupported",
            "503" to "InternxtApiException",
            "524" to "InternxtApiException",
            "401" to "AuthenticationException",
            "unreadable item" to "InternxtUnreadableResponse",
            "no JSON" to "InternxtUnreadableResponse",
            "no array of the route" to "InternxtUnreadableResponse",
            "an error object without arrays" to "InternxtUnreadableResponse",
            "404 on a later page" to "InternxtApiException",
        )

    // Runs every scenario over the same fake gateway code the other tests use, at [level], and returns the log lines and the
    // text of what each call ended in (a result or an exception with its whole chain and stack).
    private suspend fun runEverything(level: Level): Pair<List<String>, Map<String, String>> {
        val outcomes = linkedMapOf<String, String>()
        val kinds = linkedMapOf<String, String>()
        val lines =
            logLinesAt(level) {
                for ((name, sharings) in scenarios()) {
                    val service = internxtApiOn(FakeDrive(emptyList(), emptyList(), sharings = sharings()))
                    val outcome = runCatching { service.listSharedByMe() }
                    service.close()
                    val failure = outcome.exceptionOrNull()
                    kinds[name] = if (failure != null) failure.javaClass.simpleName else outcome.getOrThrow().javaClass.simpleName
                    outcomes[name] = if (failure != null) failure.stackTraceToString() else outcome.getOrThrow().toString()
                }
            }
        assertEquals(expectedOutcomes, kinds, "every scenario ended the way its name says, or the test proves nothing about it")
        return lines to outcomes
    }

    private fun assertNoCanary(
        level: Level,
        lines: List<String>,
        outcomes: Map<String, String>,
    ) {
        assertTrue(lines.any { "→ req=" in it && "/sharings/folders" in it }, "the capture sees the request lines of the HTTP layer at $level")
        assertTrue(lines.any { it.startsWith("WARN") && " 400 " in it }, "and the line of a failed answer")
        assertEquals(emptyList(), lines.filter { Canary.foundIn(it).isNotEmpty() }.map { it.take(300) }, "log lines at $level")
        assertEquals(emptyMap(), outcomes.filterValues { Canary.foundIn(it).isNotEmpty() }.mapValues { it.value.take(300) }, "results and failures at $level")
    }

    @Test
    fun `no log line of a whole listing at DEBUG holds a canary, for answers that succeed and for ones that fail`() =
        runTest {
            val (lines, outcomes) = runEverything(Level.DEBUG)

            assertNoCanary(Level.DEBUG, lines, outcomes)
        }

    @Test
    fun `no log line of a whole listing at TRACE holds a canary, for answers that succeed and for ones that fail`() =
        runTest {
            val (lines, outcomes) = runEverything(Level.TRACE)

            assertNoCanary(Level.TRACE, lines, outcomes)
        }

    @Test
    fun `the log capture does see a canary that is logged, in a message, an argument or an exception`() =
        runTest {
            val log = LoggerFactory.getLogger("canary.selftest")

            val lines =
                logLinesAt(Level.TRACE) {
                    log.debug("token {}", Canary.TOKEN)
                    log.trace("key " + Canary.ENCRYPTION_KEY)
                    log.warn("failed", IllegalStateException("pass " + Canary.NETWORK_PASS, IllegalArgumentException(Canary.OWNER_EMAIL)))
                }

            assertEquals(3, lines.count { Canary.foundIn(it).isNotEmpty() })
        }

    @Test
    fun `the text of a failure on the sharings routes holds no canary even when the gateway echoes them`() =
        runTest {
            for (status in listOf(400, 401, 403, 429, 500, 502, 503, 504, 520, 524)) {
                val service = internxtApiOn(FakeDrive(emptyList(), emptyList(), sharings = FakeSharings(failure = { _, _ -> status })))

                val failure = checkNotNull(runCatching { service.listSharedByMe() }.exceptionOrNull()) { "HTTP $status fails" }

                assertEquals(emptyList(), Canary.foundIn(failure.stackTraceToString()), "HTTP $status")
                service.close()
            }
        }
}
