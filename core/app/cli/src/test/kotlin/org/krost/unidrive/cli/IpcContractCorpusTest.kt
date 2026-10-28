package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewList
import org.krost.unidrive.ViewRef
import org.krost.unidrive.engine.ViewSpec
import org.krost.unidrive.hydration.CreateResult
import org.krost.unidrive.hydration.DehydrateResult
import org.krost.unidrive.hydration.HydrateResult
import org.krost.unidrive.hydration.Hydration
import org.krost.unidrive.hydration.HydrationError
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.LastSyncedResult
import org.krost.unidrive.hydration.ListResult
import org.krost.unidrive.hydration.RefuseNameResult
import org.krost.unidrive.hydration.MatchesLocalResult
import org.krost.unidrive.hydration.MkdirResult
import org.krost.unidrive.hydration.OpenResult
import org.krost.unidrive.hydration.RenameResult
import org.krost.unidrive.hydration.RmdirResult
import org.krost.unidrive.hydration.UnlinkResult
import org.krost.unidrive.hydration.ViewPageResult
import org.krost.unidrive.hydration.serialiseHydrationEvent
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden NDJSON contract corpus for the daemon's IPC wire (issue: one
 * versioned, executable contract spine shared by the JVM daemon and the
 * Rust/C# co-clients instead of compatibility-by-inspection).
 *
 * Corpus layout: `src/test/resources/ipc-contract/<verb>.ndjson`, one file
 * per IPC verb the daemon registers. Each file holds canonical
 * request/response pairs as alternating NDJSON lines (odd line = request,
 * even line = response). Co-client repos vendor these files and replay them
 * against their own encoder/decoder.
 *
 * The daemon authenticates every connection first (docs/dev/specs/ipc-authentication.md); the
 * handshake itself and its refusal shapes are pinned by the session transcripts under
 * `ipc-contract/auth/` (one connection per file, request/reply pairs in order).
 * Lines the daemon writes on a connection without a request (no verb) live
 * in `ipc-contract/connection/<error token>.ndjson`, one line each: today
 * only `too_many_clients`, the line a connection over the cap reads before
 * the daemon closes it.
 *
 * Comparison is parse-and-compare (key-order-insensitive), never strcmp.
 * A small set of VOLATILE_FIELDS carry machine- or run-dependent values
 * (uuids, uptimes, local cache paths); for those the corpus value is
 * representative and the assertion pins presence + JSON type only.
 */
class IpcContractCorpusTest {
    // The non-hydration verbs DaemonRuntime registers inline (see the
    // server.registerHandler calls in DaemonRuntime.start). daemon.status is
    // listed first so its refresh_in_flight:false and idle-enumeration
    // expectations are checked before this test launches a refresh job or an
    // enumeration; daemon.pause is followed by daemon.resume before any verb a paused daemon would refuse;
    // daemon.shutdown is listed last because it stops the daemon the other verbs are replayed against.
    private val daemonVerbs =
        listOf(
            "daemon.status", "daemon.pause", "daemon.resume", "metrics.query", "sync.subscribe", "refresh.run", "sync.enumerate",
            "history.list", "history.subscribe", "ignore.explain",
            // auth.logout deletes the corpus daemon's (absent) credential files and is idempotent; it must
            // run before daemon.shutdown, which stops the daemon the other verbs are replayed against.
            "auth.logout",
            // The sign-in verbs (§4.11) against the corpus profile: refusals and a sign-in with fake credentials.
            "auth.begin", "auth.complete", "auth.login", "auth.check",
            "daemon.shutdown",
        )

    @Test
    fun corpus_covers_every_daemon_ipc_verb() {
        val dirUrl = checkNotNull(javaClass.getResource("/ipc-contract")) {
            "ipc-contract corpus directory missing from test resources"
        }
        val files = Files.list(Paths.get(dirUrl.toURI())).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".ndjson") }.toList()
        }
        val expected = (HydrationIpcHandler.VERBS + daemonVerbs).map { "$it.ndjson" }.toSet()
        assertEquals(expected, files.toSet(), "one corpus fixture per registered IPC verb, no extras")

        // IPC authentication: every corpus verb has a verb class (the default deny cannot silently
        // swallow a real verb), and the class table names no verb outside the corpus, i.e. none the
        // daemon does not register.
        assertEquals(
            files.map { it.removeSuffix(".ndjson") }.toSet(),
            IpcAuth.VERB_CLASSES.keys,
            "verb classes (IpcAuth.VERB_CLASSES) must cover exactly the corpus verbs",
        )

        // Corpus self-consistency: every request line in <verb>.ndjson must
        // actually carry that verb.
        for (verb in HydrationIpcHandler.VERBS + daemonVerbs) {
            for ((request, _) in loadPairs(verb)) {
                val req = Json.parseToJsonElement(request)
                assertEquals(
                    JsonPrimitive(verb),
                    (req as JsonObject)["verb"],
                    "request line in $verb.ndjson names a different verb",
                )
            }
        }
    }

    @Test
    fun hydration_verb_replies_match_corpus() = runBlocking {
        // A fixed run part, so the write-handle ids the daemon issues are the ones the corpus spells.
        val handler = HydrationIpcHandler(ScriptedHydration(), handleIdRun = "0123456789ab")
        for (verb in HydrationIpcHandler.VERBS) {
            for ((request, expectedReply) in loadPairs(verb)) {
                val reply = handler.handle(connectionId = "conn-contract", jsonRequest = request)
                assertJsonMatches(
                    expected = Json.parseToJsonElement(expectedReply),
                    actual = Json.parseToJsonElement(reply),
                    at = "$verb reply",
                )
            }
        }
    }

    @Test
    fun daemon_verb_replies_match_corpus_over_live_socket() = runBlocking {
        val tempDir = Files.createTempDirectory("ipc-contract-test")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            // archive#655: the corpus pins the no-account-quota shape (localfs-like) — a quota-bearing
            // provider's snapshot values are machine-dependent, so daemon.status here carries
            // no `quota` field; the quota-present shape is pinned in DaemonRuntimeTest.
            providerHasQuota = false,
        )
        // history.list's corpus is replayed against four rows with fixed ids and instants.
        // A clock near the rows keeps the retention (90 days) from taking the old rows away from under the corpus.
        runtime.openHistory = { profile, file ->
            TransferHistory.open(profile, file, clock = { 1_700_000_100_000L }).also { seedHistoryCorpus(file) }
        }
        // The sign-in verbs (§4.11) sign an Internxt-typed profile in; the library sign-in is a fake that knows one
        // password, one bad two-factor code and one other user (no network, no real account).
        runtime.signInTarget = ProfileTarget("contract_profile", "internxt", tempDir)
        val realLogin = LifecycleHooks.internxtLogin
        LifecycleHooks.internxtLogin = { _, email, password, totp ->
            when {
                password != "fake-password" -> throw org.krost.unidrive.internxt.BadCredentialsException("rejected")
                totp == "000000" -> throw org.krost.unidrive.internxt.TfaInvalidException("rejected")
                // Another user than the one the profile's state belongs to (AccountOwner): refused before storing.
                email == "other@example.test" -> throw org.krost.unidrive.AccountChangedException()
                else ->
                    org.krost.unidrive.internxt.model.InternxtCredentials(
                        jwt = "fake-jwt", mnemonic = "fake-mnemonic", rootFolderId = "r", email = email, bridgeUser = email, bridgeUserId = "u", bucket = "b",
                    )
            }
        }
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)

            for (verb in daemonVerbs) {
                for ((request, expectedReply) in loadPairs(verb)) {
                    // Fresh connection per exchange: subscribe-style verbs turn
                    // the connection into an event stream after the reply, so a
                    // shared connection would leak pushed events into the next
                    // verb's reply read. Each one authenticates first (the daemon
                    // writes its tokens next to the lock).
                    val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "contract_profile"), IpcAuth.Scope.FULL)
                    try {
                        channel.configureBlocking(false)
                        channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                        val reply = readFirstLine(channel, timeoutMs = 5_000)
                        assertJsonMatches(
                            expected = Json.parseToJsonElement(expectedReply),
                            actual = Json.parseToJsonElement(reply),
                            at = "$verb reply",
                        )
                    } finally {
                        channel.close()
                    }
                }
            }
        } finally {
            runtime.close()
            daemonJob.join()
            LifecycleHooks.internxtLogin = realLogin
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/pause-cycle.ndjson`: request/reply pairs replayed in order on ONE connection against a live
     * mount daemon (archive#778): a pause, the status that shows it, a refused reason, the resume, the status without
     * it. `events/paused.ndjson` and `events/resumed.ndjson` are the lines the same pause and resume push to a
     * subscriber, one line each.
     */
    @Test
    fun pause_session_and_events_match_corpus_over_live_socket() = replayPauseSession("pause-cycle", pausedEvent = "paused")

    /**
     * `sessions/pause-timed.ndjson`: a pause with `until_ms`, its two refusals, the status that shows the end instant and
     * the resume. The instant in the transcript is far in the future (the daemon is live), so it is pinned exactly.
     */
    @Test
    fun timed_pause_session_and_events_match_corpus_over_live_socket() = replayPauseSession("pause-timed", pausedEvent = "paused-timed")

    /**
     * The line pushed when a timed pause runs out cannot be waited for on a live socket; `events/resumed-timeout.ndjson`
     * is pinned against the serialiser both streams share, next to the other pause lines.
     */
    @Test
    fun pause_event_lines_match_the_serialiser() {
        fun same(
            fixture: String,
            event: HydrationEvent,
        ) = assertJsonMatches(
            expected = Json.parseToJsonElement(loadEventLine(fixture)),
            actual = Json.parseToJsonElement(serialiseHydrationEvent(event)),
            at = "events/$fixture",
        )
        same("paused", HydrationEvent.Paused("metered", 1_700_000_000_000L, true))
        same("paused-timed", HydrationEvent.Paused("user", 1_700_000_000_000L, false, untilMs = 4_102_444_800_000L))
        same("resumed", HydrationEvent.Resumed())
        same("resumed-timeout", HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT))
    }

    private fun replayPauseSession(
        sessionName: String,
        pausedEvent: String,
    ) = runBlocking {
        val pairs = loadSession(sessionName)
        val expectedPaused = loadEventLine(pausedEvent)
        val expectedResumed = loadEventLine("resumed")
        val tempDir = Files.createTempDirectory("ipc-contract-pause-test")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            providerHasQuota = false,
        )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val endpoint = IpcEndpoint(socketPath, tempDir, "contract_profile")
            val subscriber = IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
            val session = IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
            try {
                subscriber.configureBlocking(false)
                session.configureBlocking(false)
                subscriber.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
                readFirstLine(subscriber, timeoutMs = 5_000)
                // sync.subscribe registers after its reply; a request on the same connection comes after that.
                subscriber.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
                readFirstLine(subscriber, timeoutMs = 5_000)

                for ((request, expectedReply) in pairs) {
                    session.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                    assertJsonMatches(
                        expected = Json.parseToJsonElement(expectedReply),
                        actual = Json.parseToJsonElement(readFirstLine(session, timeoutMs = 5_000)),
                        at = "$sessionName: reply to $request",
                    )
                    val verb = ((Json.parseToJsonElement(request) as JsonObject)["verb"] as JsonPrimitive).content
                    val accepted = expectedReply.contains("\"ok\":true")
                    if (verb == "daemon.pause" && accepted) {
                        assertJsonMatches(
                            expected = Json.parseToJsonElement(expectedPaused),
                            actual = Json.parseToJsonElement(readFirstLine(subscriber, timeoutMs = 5_000)),
                            at = "paused event",
                        )
                    }
                    if (verb == "daemon.resume" && accepted) {
                        assertJsonMatches(
                            expected = Json.parseToJsonElement(expectedResumed),
                            actual = Json.parseToJsonElement(readFirstLine(subscriber, timeoutMs = 5_000)),
                            at = "resumed event",
                        )
                    }
                }
            } finally {
                subscriber.close()
                session.close()
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/trash-status.ndjson` and `sessions/trash-status-renamed.ndjson`: the `trash` object of `daemon.status`
     * (docs/dev/specs/unidrive-daemon-design.md section 4.3), present only once a listing has placed an item in the
     * provider's trash. Each transcript is a `sync.enumerate` and the `daemon.status` that follows it, replayed against a
     * live mount daemon whose provider lists one trashed item. In the second one a live top-level folder already has the
     * default name, so the root takes `Papierkorb (2)` while `configured_name` stays what the profile asks for.
     * The status is asked until the enumeration has landed; run-dependent instants stand in as in the history event.
     */
    @Test
    fun daemon_status_with_a_trash_matches_corpus_over_live_socket() {
        val trashed = CloudItem("t1", "a.txt", "/Old/a.txt", 1, false, null, null, null, null, trashed = true)
        replayTrashStatus("trash-status", listOf(trashed))
        replayTrashStatus(
            "trash-status-renamed",
            listOf(CloudItem("live-pk", "Papierkorb", "/Papierkorb", 0, true, null, null, null, null), trashed),
        )
    }

    private fun replayTrashStatus(
        session: String,
        listing: List<CloudItem>,
    ) = runBlocking {
        val pairs = loadSession(session)
        assertEquals(2, pairs.size, "$session: one enumerate and one status")
        val tempDir = Files.createTempDirectory("ipc-contract-trash-status")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider(listing) },
            providerHasQuota = false,
        )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val endpoint = IpcEndpoint(socketPath, tempDir, "contract_profile")

            suspend fun ask(request: String): String {
                val channel = IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
                try {
                    channel.configureBlocking(false)
                    channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                    return readFirstLine(channel, timeoutMs = 5_000)
                } finally {
                    channel.close()
                }
            }

            val (enumerate, enumerated) = pairs[0]
            assertJsonMatches(Json.parseToJsonElement(enumerated), Json.parseToJsonElement(ask(enumerate)), "$session: reply to $enumerate")

            val (statusRequest, expectedStatus) = pairs[1]
            val expected = Json.parseToJsonElement(expectedStatus) as JsonObject
            // The enumeration runs after its reply: ask until it has landed (idle, a success instant, the trash placed).
            fun JsonObject.section(key: String): JsonObject = getValue(key) as JsonObject
            fun landed(reply: JsonObject): Boolean =
                "trash" in reply &&
                    (reply.section("enumeration")["state"] as? JsonPrimitive)?.content == "idle" &&
                    reply.section("enumeration")["last_success_at_ms"].let { it != null && it !is JsonNull } &&
                    reply.section("provider_health")["last_contact_ms"].let { it != null && it !is JsonNull }
            val deadline = System.currentTimeMillis() + 10_000
            var reply: JsonObject
            do {
                reply = Json.parseToJsonElement(ask(statusRequest)) as JsonObject
                if (landed(reply)) break
                delay(50)
            } while (System.currentTimeMillis() < deadline)
            assertTrue(landed(reply), "$session: the enumeration did not land within 10 s; last status: $reply")

            // The instants of the run: each must be a number, and the transcript's value stands in for it.
            val instants = mapOf("enumeration" to listOf("started_at_ms", "last_success_at_ms"), "provider_health" to listOf("last_contact_ms"))
            val comparable =
                JsonObject(
                    reply + instants.mapValues { (section, keys) ->
                        for (key in keys) {
                            val value = reply.section(section).getValue(key)
                            assertTrue(value is JsonPrimitive && !value.isString && value !is JsonNull, "$section.$key is a number, got $value")
                        }
                        JsonObject(reply.section(section) + keys.associateWith { expected.section(section).getValue(it) })
                    },
                )
            assertJsonMatches(expected, comparable, "$session: reply to $statusRequest")
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/views-recents.ndjson`: the Recents view on a live mount daemon. state.db holds six rows (three folders, three
     * files, two of them called todo.txt) and the provider lists four references to them (one without a row). The transcript
     * is the top level with the view's root, the view's entries (the namesakes told apart, the reference without a row
     * skipped), `daemon.status` with its `views` object and the `views` capability, reads through an entry (the cache file is
     * the real item's), and every verb that would write answering `read_only`; the real item is listed where it was afterwards.
     * The root's own mtime is the daemon's start, so the transcript's stands in for it. No client subscribes here: a
     * subscriber on the hydration stream makes the daemon enumerate once, and the stub's empty listing would reap the rows.
     */
    @Test
    fun views_session_matches_corpus_over_live_socket() {
        replayViewSession("views-recents", StubProvider(viewRefs = recentsRefs()))
    }

    /**
     * `events/view.invalidated-recents.ndjson` is also what a subscriber of the hydration stream is pushed on a live daemon when
     * the first list of the view lands. The subscribe makes the daemon enumerate once (its listing is the stub's), so the
     * stream may carry the engine's own `view.invalidated` for the rows too: the line is read for among them.
     */
    @Test
    fun view_invalidated_is_pushed_to_a_subscriber_when_the_list_of_a_view_lands() = runBlocking {
        val expected = Json.parseToJsonElement(loadEventLine("view.invalidated-recents"))
        val tempDir = Files.createTempDirectory("ipc-contract-view-event")
        val socketPath = tempDir.resolve("daemon.sock")
        val file = CloudItem("22222222-todo", "todo.txt", "/todo.txt", 5, false, null, null, null, null)
        val provider = StubProvider(listing = listOf(file), viewRefs = mapOf(ViewKind.RECENTS to listOf(ViewRef("22222222-todo", false))))
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
            providerHasQuota = false,
            views = listOf(ViewSpec(ViewKind.RECENTS, limit = 10)),
        )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val endpoint = IpcEndpoint(socketPath, tempDir, "contract_profile")
            val newline = 10.toChar()
            val subscriber = IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
            val client = IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
            try {
                subscriber.configureBlocking(false)
                client.configureBlocking(false)
                subscriber.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + newline).toByteArray()))
                readFirstLine(subscriber, timeoutMs = 5_000)
                client.write(ByteBuffer.wrap(("""{"verb":"hydration.list","prefix":"/Recents"}""" + newline).toByteArray()))
                readFirstLine(client, timeoutMs = 5_000)

                val pending = StringBuilder()
                val deadline = System.currentTimeMillis() + 10_000
                var seen = false
                while (!seen && System.currentTimeMillis() < deadline) {
                    seen = Json.parseToJsonElement(readLine(subscriber, pending, timeoutMs = 5_000)) == expected
                }
                assertTrue(seen, "the subscriber was pushed $expected")
            } finally {
                subscriber.close()
                client.close()
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/views-shared.ndjson`: the Shared view (what the account shares) on a live mount daemon, over the same rows. The
     * provider lists three references, one without a row. The kind is told apart from Recents by its words only: `shared` in
     * `view`, in the keys `unidrive:view:shared[:<id>]` and in `daemon.status`, and the root `/Shared`. Reads and refusals
     * are those of any view.
     */
    @Test
    fun shared_view_session_matches_corpus_over_live_socket() {
        replayViewSession(
            "views-shared",
            StubProvider(viewRefs = sharedRefs()),
            spec = ViewSpec(ViewKind.SHARED_BY_ME, limit = 5_000),
        )
    }

    /**
     * `sessions/views-favorites.ndjson`: a view whose provider references a folder: the folder is a read-only alias of the real
     * folder (`/Favorites/Work` lists the real content under the alias prefix to depth three, keys `unidrive:view:favorites:<id>`,
     * `view_target` the real path), a prefix that is only the start of a name matches nothing, reads resolve to the real item
     * and every write verb on the entry, the alias root and below it answers `read_only`. The reference without a row is skipped.
     */
    @Test
    fun favorites_alias_session_matches_corpus_over_live_socket() {
        replayViewSession(
            "views-favorites",
            StubProvider(viewRefs = favoritesRefs()),
            spec = ViewSpec(ViewKind.FAVORITES, limit = 5_000),
        )
    }

    /**
     * `sessions/views-unavailable.ndjson`: a provider that declares the view but answers that it has none (a server without the
     * route): the root is there and empty, and the state says so.
     */
    @Test
    fun views_session_for_a_provider_without_the_view_matches_corpus_over_live_socket() {
        replayViewSession("views-unavailable", StubProvider(declared = setOf(ViewKind.RECENTS)))
    }

    /**
     * `sessions/views-undeclared.ndjson`: the profile asks for the view, the provider does not declare it: no root at the top
     * level, no `views` in `daemon.status` and no `views` capability, as for a profile that shows none.
     */
    @Test
    fun views_session_for_a_provider_that_does_not_declare_the_view_matches_corpus_over_live_socket() {
        replayViewSession("views-undeclared", StubProvider())
    }

    /**
     * `sessions/views-truncated.ndjson`: the provider has four references and the view asks for two: the provider cuts the
     * list and says so, the view shows the first two and `daemon.status` says `truncated`.
     */
    @Test
    fun views_session_with_a_cut_list_matches_corpus_over_live_socket() {
        replayViewSession("views-truncated", StubProvider(viewRefs = recentsRefs()), spec = ViewSpec(ViewKind.RECENTS, limit = 2))
    }

    /**
     * `sessions/views-recents-paging.ndjson`: `view.page` on a live mount daemon whose folder lists two of the four
     * references and may page to 1000. The first page is the folder's two (the list goes on); the second is cut from a longer
     * list (the reference without a row skipped) and ends it; a page past the cap is empty and ends it; a kind the profile does
     * not show is `unknown_view`; the folder still lists its two afterwards. The entries of a page are the real rows.
     */
    @Test
    fun views_paging_session_matches_corpus_over_live_socket() {
        replayViewSession(
            "views-recents-paging",
            StubProvider(viewRefs = recentsRefs()),
            spec = ViewSpec(ViewKind.RECENTS, limit = 2, pageCap = 1_000),
        )
    }

    /**
     * `sessions/views-stale.ndjson`: the list is fetched at the first listing, the refresh behind the second one fails: the
     * list before it is still served and `daemon.status` says `stale` (the time to live is shortened so that the second
     * listing refreshes).
     */
    @Test
    fun views_session_with_a_failing_refresh_matches_corpus_over_live_socket() {
        val provider = StubProvider(viewRefs = recentsRefs())
        replayViewSession(
            "views-stale",
            provider,
            viewTtlMs = 1,
            beforeExchange = { index -> if (index == 1) provider.viewFailure = java.io.IOException("connection reset") },
            waitForViewState = "stale",
        )
    }

    /** `events/view.invalidated-recents.ndjson` is the line the daemon's serialiser makes of the event the view's cache sends. */
    @Test
    fun view_invalidated_event_matches_the_serialiser() {
        assertJsonMatches(
            expected = Json.parseToJsonElement(loadEventLine("view.invalidated-recents")),
            actual = Json.parseToJsonElement(serialiseHydrationEvent(HydrationEvent.ViewInvalidated(paths = listOf("/Recents")))),
            at = "events/view.invalidated-recents",
        )
    }

    /**
     * `events/failed-parent-gone.ndjson` and `events/completed-parent-gone.ndjson`: the lines a subscriber of the hydration
     * stream is pushed for an upload whose folder the provider's walk proved gone in the cloud (archive#580). The shape is the
     * one every failed upload has; only the `error` text is the typed `parent_gone: <folder>` token, so a client that does
     * not know the token reads a failed upload that is not retried, as before the token existed.
     */
    @Test
    fun parent_gone_event_lines_match_the_serialiser() {
        val gone = HydrationError.ParentGone("/Trashed folder")
        assertJsonMatches(
            expected = Json.parseToJsonElement(loadEventLine("failed-parent-gone")),
            actual = Json.parseToJsonElement(serialiseHydrationEvent(HydrationEvent.Failed("/Trashed folder/report.docx", gone, retryScheduled = false))),
            at = "events/failed-parent-gone",
        )
        assertJsonMatches(
            expected = Json.parseToJsonElement(loadEventLine("completed-parent-gone")),
            actual =
                Json.parseToJsonElement(
                    serialiseHydrationEvent(
                        HydrationEvent.Completed(
                            path = "/Trashed folder/report.docx",
                            handleId = "h1",
                            direction = HydrationEvent.Completed.Direction.UPLOAD,
                            ok = false,
                            error = gone,
                        ),
                    ),
                ),
            at = "events/completed-parent-gone",
        )
    }

    private fun recentsRefs() =
        mapOf(
            ViewKind.RECENTS to
                listOf(ViewRef("22222222-todo", false), ViewRef("33333333-todo", false), ViewRef("11111111-q3", false), ViewRef("44444444-gone", false)),
        )

    private fun favoritesRefs() =
        mapOf(ViewKind.FAVORITES to listOf(ViewRef("rid-work", true), ViewRef("22222222-todo", false), ViewRef("55555555-gone", true)))

    private fun sharedRefs() =
        mapOf(ViewKind.SHARED_BY_ME to listOf(ViewRef("22222222-todo", false), ViewRef("11111111-q3", false), ViewRef("44444444-gone", false)))

    // The rows the view sessions are written against, with the instants their listing carries.
    private fun seedViewRows(dbPath: Path) {
        fun row(path: String, id: String, folder: Boolean, hash: String? = null) =
            SyncEntry(
                path = path, remoteId = id, remoteHash = hash, remoteSize = if (folder) 0 else 5,
                remoteModified = Instant.ofEpochMilli(FIXED_REMOTE_MS), localMtime = null, localSize = null,
                isFolder = folder, isPinned = false, isHydrated = false, lastSynced = Instant.ofEpochMilli(FIXED_MTIME_MS),
            )
        val db = StateDatabase(dbPath)
        db.initialize()
        try {
            db.upsertEntry(row("/Notes", "rid-notes", folder = true))
            db.upsertEntry(row("/Notes/todo.txt", "22222222-todo", folder = false, hash = "etag-todo"))
            db.upsertEntry(row("/Work", "rid-work", folder = true))
            db.upsertEntry(row("/Work/Reports", "rid-reports", folder = true))
            db.upsertEntry(row("/Work/Reports/q3.pdf", "11111111-q3", folder = false, hash = "etag-q3"))
            db.upsertEntry(row("/Work/todo.txt", "33333333-todo", folder = false, hash = "etag-todo-2"))
        } finally {
            db.close()
        }
    }

    // The root of a view has the daemon's start as its mtime (stable for the run, so a listing does not make it flicker):
    // it must be a number, and the transcript's stands in for it.
    private fun standInForRootMtime(actual: JsonElement): JsonElement {
        val reply = actual as? JsonObject ?: return actual
        val entries = reply["entries"] as? JsonArray ?: return actual
        val standIn =
            entries.map { entry ->
                val e = entry as JsonObject
                if (e["view"] != null && e["view_target"] == null) {
                    val mtime = e.getValue("mtime_ms")
                    assertTrue(mtime is JsonPrimitive && !mtime.isString && mtime !is JsonNull, "a view root's mtime_ms is a number, got $mtime")
                    JsonObject(e + ("mtime_ms" to JsonPrimitive(FIXED_MTIME_MS)))
                } else {
                    e
                }
            }
        return JsonObject(reply + ("entries" to JsonArray(standIn)))
    }

    /**
     * Replays a view transcript, one exchange per connection, against a live mount daemon showing the view [spec] (Recents
     * unless said otherwise) over the rows of [seedViewRows]. [waitForViewState] asks `daemon.status` until its
     * `views[0].state` is that word.
     */
    private fun replayViewSession(
        session: String,
        provider: StubProvider,
        viewTtlMs: Long = 60_000L,
        beforeExchange: (index: Int) -> Unit = {},
        waitForViewState: String? = null,
        spec: ViewSpec = ViewSpec(ViewKind.RECENTS, limit = 10),
    ) = runBlocking {
        val pairs = loadSession(session)
        val tempDir = Files.createTempDirectory("ipc-contract-views")
        val socketPath = tempDir.resolve("daemon.sock")
        seedViewRows(tempDir.resolve("state.db"))
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
            providerHasQuota = false,
            views = listOf(spec),
            viewTtlMs = viewTtlMs,
        )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val endpoint = IpcEndpoint(socketPath, tempDir, "contract_profile")
            val newline = 10.toChar()
            val backslash = 92.toChar()

            suspend fun ask(request: String): String {
                val channel = IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
                try {
                    channel.configureBlocking(false)
                    channel.write(ByteBuffer.wrap((request + newline).toByteArray()))
                    return readFirstLine(channel, timeoutMs = 5_000)
                } finally {
                    channel.close()
                }
            }

            fun viewState(reply: String): String? =
                (((Json.parseToJsonElement(reply) as JsonObject)["views"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("state")?.let { (it as JsonPrimitive).content }

            for ((index, pair) in pairs.withIndex()) {
                val (request, expectedReply) = pair
                beforeExchange(index)
                val verb = ((Json.parseToJsonElement(request) as JsonObject)["verb"] as JsonPrimitive).content
                var reply = ask(request)
                if (waitForViewState != null && verb == "daemon.status") {
                    val deadline = System.currentTimeMillis() + 10_000
                    while (viewState(reply) != waitForViewState && System.currentTimeMillis() < deadline) {
                        delay(50)
                        reply = ask(request)
                    }
                }
                val actual = standInForRootMtime(Json.parseToJsonElement(reply))
                assertJsonMatches(Json.parseToJsonElement(expectedReply), actual, "$session: reply to $request")
                // A read through an entry hands out the real item's cache file, not a copy at the view's path.
                if (verb == "hydration.open_read" && "cache_path" in expectedReply) {
                    val cachePath = ((actual as JsonObject).getValue("cache_path") as JsonPrimitive).content
                    assertTrue(cachePath.replace(backslash, '/').endsWith("/Work/Reports/q3.pdf"), "the real item's cache file: $cachePath")
                }
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `cli/auth.complete*.ndjson`: argv line and reply line of the `auth complete` command (a command line, not an IPC verb;
     * AuthLifecycleTest replays them). One carries the additive `account` field, the other has none.
     */
    @Test
    fun cli_auth_complete_fixtures_pin_the_optional_account_field() {
        fun reply(name: String): JsonObject {
            val lines = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/cli/$name")) { "$name missing" }
                .bufferedReader().readLines().filter { it.isNotBlank() }
            assertEquals(2, lines.size, name)
            return Json.parseToJsonElement(lines[1]) as JsonObject
        }
        val with = reply("auth.complete.ndjson")
        val without = reply("auth.complete-no-account.ndjson")
        assertEquals("authenticated", (with["status"] as JsonPrimitive).content)
        assertTrue("account" in with)
        assertEquals("authenticated", (without["status"] as JsonPrimitive).content)
        assertTrue("account" !in without, "a provider without an account name sends no key")
    }

    /**
     * `auth/<name>.ndjson`: one session each, replayed in order on ONE connection against a server
     * holding the fixed test vectors (profile `p1`, token bytes 00..1f for both scopes, server nonce
     * 10..1f). They pin the handshake replies and the auth_required / auth_failed / forbidden shapes.
     */
    @Test
    fun auth_transcripts_replay_against_the_handshake() = runBlocking {
        val dirUrl = checkNotNull(javaClass.getResource("/ipc-contract/auth")) { "ipc-contract/auth missing" }
        val transcripts = Files.list(Paths.get(dirUrl.toURI())).use { s -> s.filter { it.toString().endsWith(".ndjson") }.toList() }
        assertTrue(transcripts.size >= 4, "auth transcripts: $transcripts")
        val token = ByteArray(32) { it.toByte() }
        for (file in transcripts) {
            val pairs = Files.readAllLines(file).filter { it.isNotBlank() }.chunked(2).map { it[0] to it[1] }
            for ((request, _) in pairs) {
                val verb = (Json.parseToJsonElement(request) as JsonObject)["verb"]!!.let { (it as JsonPrimitive).content }
                assertTrue(
                    verb == IpcAuth.HELLO || verb == IpcAuth.HELLO_PROOF || verb in IpcAuth.VERB_CLASSES,
                    "${file.fileName}: '$verb' is neither a handshake verb nor a classified verb",
                )
            }
            val dir = Files.createTempDirectory("ipc-contract-auth")
            val socketPath = dir.resolve("a.sock")
            val auth = IpcAuth("p1", token, token, "contract", serverNonces = { ByteArray(16) { (0x10 + it).toByte() } })
            val server = IpcServer(socketPath, auth = auth)
            val serveJob = kotlinx.coroutines.SupervisorJob()
            server.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + serveJob))
            try {
                SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
                    channel.configureBlocking(false)
                    for ((request, expectedReply) in pairs) {
                        channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                        assertJsonMatches(
                            expected = Json.parseToJsonElement(expectedReply),
                            actual = Json.parseToJsonElement(readFirstLine(channel, timeoutMs = 5_000)),
                            at = "${file.fileName}: reply to $request",
                        )
                    }
                }
            } finally {
                server.close()
                serveJob.cancel()
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
    }

    @Test
    fun connection_refusal_matches_corpus_over_live_socket() = runBlocking {
        val expected = loadConnectionLine("too_many_clients")
        val tempDir = Files.createTempDirectory("ipc-contract-refusal-test")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            // archive#655: the corpus pins the no-account-quota shape (localfs-like) — a quota-bearing
            // provider's snapshot values are machine-dependent, so daemon.status here carries
            // no `quota` field; the quota-present shape is pinned in DaemonRuntimeTest.
            providerHasQuota = false,
        )
        val daemonJob = launch { runtime.start() }
        val held = mutableListOf<SocketChannel>()
        try {
            awaitDaemonSocket(socketPath)

            // Each connection is confirmed by a daemon.status reply before the next one opens,
            // so the daemon has counted it; the first connection past the cap reads the refusal
            // line instead of the reply.
            var refusal: String? = null
            for (attempt in 1..64) {
                val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
                held += channel
                channel.configureBlocking(false)
                channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
                val line = readFirstLine(channel, timeoutMs = 5_000)
                val error = (Json.parseToJsonElement(line) as JsonObject)["error"]
                if ((error as? JsonPrimitive)?.content == "too_many_clients") {
                    refusal = line
                    break
                }
            }
            assertJsonMatches(
                expected = Json.parseToJsonElement(expected),
                actual = Json.parseToJsonElement(checkNotNull(refusal) { "no connection was refused" }),
                at = "too_many_clients line",
            )
        } finally {
            held.forEach { runCatching { it.close() } }
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/history-unavailable.ndjson`: a mirror profile records no transfer history, so both history verbs answer
     * `history_unavailable` (never an empty list that reads as "no transfers").
     */
    @Test
    fun history_verbs_on_a_mirror_daemon_match_corpus_over_live_socket() = runBlocking {
        val pairs = loadSession("history-unavailable")
        val tempDir = Files.createTempDirectory("ipc-contract-history-mirror")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MIRROR,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir.resolve("sync-root"),
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            providerHasQuota = false,
        )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            for ((request, expectedReply) in pairs) {
                val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "contract_profile"), IpcAuth.Scope.FULL)
                try {
                    channel.configureBlocking(false)
                    channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                    assertJsonMatches(
                        expected = Json.parseToJsonElement(expectedReply),
                        actual = Json.parseToJsonElement(readFirstLine(channel, timeoutMs = 5_000)),
                        at = "history-unavailable: reply to $request",
                    )
                } finally {
                    channel.close()
                }
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/history-retry.ndjson` (the attempt limit and the next try, docs/dev/specs/unidrive-daemon-design.md section 4.8):
     * an upload waiting in a retry backoff (`running`, `attempts` 1 of `max_attempts` 6, `next_try_at_ms` set), one that used all
     * its attempts (`failed`, no next try), one whose retry is under way (`running`, no next try) and a download (no limit).
     */
    @Test
    fun history_rows_of_retrying_uploads_match_corpus_over_live_socket() = runBlocking {
        val pairs = loadSession("history-retry")
        val tempDir = Files.createTempDirectory("ipc-contract-history-retry")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            providerHasQuota = false,
        )
        runtime.openHistory = { profile, file ->
            TransferHistory.open(profile, file, clock = { 1_700_000_100_000L }).also { seedHistoryRetryCorpus(file) }
        }
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            for ((request, expectedReply) in pairs) {
                val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "contract_profile"), IpcAuth.Scope.READ)
                try {
                    channel.configureBlocking(false)
                    channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                    assertJsonMatches(
                        expected = Json.parseToJsonElement(expectedReply),
                        actual = Json.parseToJsonElement(readFirstLine(channel, timeoutMs = 5_000)),
                        at = "history-retry: reply to $request",
                    )
                } finally {
                    channel.close()
                }
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `sessions/uploads-retrying.ndjson`: the `daemon.status` of a mount daemon with two uploads in a retry backoff
     * (`uploads.retrying` 2, `uploads.next_try_at_ms` the earlier of their next attempts). A daemon in that state cannot be
     * waited for on a live socket, so the transcript is pinned against the serialiser of `uploads` and against the idle
     * `daemon.status.ndjson` it differs from in that one object.
     */
    @Test
    fun daemon_status_with_uploads_in_a_retry_backoff_matches_the_serialiser_and_the_idle_corpus() {
        val (request, reply) = loadSession("uploads-retrying").single()
        val (idleRequest, idleReply) = loadPairs("daemon.status").single()
        assertEquals(idleRequest, request)
        val health =
            HydrationImpl.UploadHealth(
                pending = 3, inFlight = 1, failed = 2, oldestPendingAgeMs = 5_400_000L, asOfMs = 1_700_000_000_200L,
                retrying = 2, nextTryAtMs = 1_700_000_060_000L,
            ).toJson()
        val expected = Json.parseToJsonElement(reply) as JsonObject
        assertJsonMatches(expected.getValue("uploads"), health, "uploads-retrying: uploads")
        val idle = Json.parseToJsonElement(idleReply) as JsonObject
        assertJsonMatches(expected, JsonObject(idle + ("uploads" to health)), "uploads-retrying: the rest of the reply")
    }

    /**
     * `sessions/uploads-running.ndjson` (archive#1200): the `daemon.status` of a mount daemon with two uploads under way, one with a
     * known size and one whose size the provider has not said (`bytes_total` null). `uploads.running` names them (capped,
     * `more` counts the rest) and the `uploads.running` capability is listed. Pinned against the serialiser, and against the
     * idle corpus it differs from in `uploads`.
     */
    @Test
    fun daemon_status_with_running_uploads_matches_the_serialiser_and_the_idle_corpus() {
        val (request, reply) = loadSession("uploads-running").single()
        val (idleRequest, idleReply) = loadPairs("daemon.status").single()
        assertEquals(idleRequest, request)
        val health =
            HydrationImpl.UploadHealth(
                pending = 2, inFlight = 2, failed = 0, oldestPendingAgeMs = 333_965L, asOfMs = 1_700_000_000_200L,
                running =
                    HydrationImpl.RunningUploads(
                        2,
                        listOf(
                            HydrationImpl.RunningItem("/Backups/archive.7z", 1_699_999_990_000L, 734_003_200L, 2_469_606_400L),
                            HydrationImpl.RunningItem("/Docs/report.docx", 1_699_999_995_000L, 0L, null),
                        ),
                    ),
            ).toJson()
        val expected = Json.parseToJsonElement(reply) as JsonObject
        assertJsonMatches(expected.getValue("uploads"), health, "uploads-running: uploads")
        val idle = Json.parseToJsonElement(idleReply) as JsonObject
        assertTrue(JsonPrimitive(RUNNING_UPLOADS_CAPABILITY) in (idle.getValue("capabilities") as JsonArray), "a mount daemon names uploads.running")
        assertJsonMatches(expected, JsonObject(idle + ("uploads" to health)), "uploads-running: the rest of the reply")
    }

    /**
     * `sessions/notifications-connected.ndjson` (archive#111): the `daemon.status` of a daemon whose provider has a connected
     * remote-change channel (OneDrive's socket.io endpoint) that has rotated twice. The corpus stub has no channel (the
     * idle `daemon.status.ndjson` says `unsupported`), so the transcript is pinned against the serialiser of
     * `notifications` and against the idle corpus it differs from in that one object. The endpoint itself never appears.
     */
    @Test
    fun daemon_status_with_a_connected_notification_channel_matches_the_serialiser_and_the_idle_corpus() {
        val (request, reply) = loadSession("notifications-connected").single()
        val (idleRequest, idleReply) = loadPairs("daemon.status").single()
        assertEquals(idleRequest, request)
        val notifications =
            notificationsStatusJson(
                org.krost.unidrive.RemoteChangeChannelStatus(
                    state = org.krost.unidrive.RemoteChangeChannelStatus.State.CONNECTED,
                    lastHintAtMs = 1_700_000_000_300L,
                    hints = 7,
                    expiresAtMs = 1_700_001_800_000L,
                    rotations = 2,
                ),
            )
        val expected = Json.parseToJsonElement(reply) as JsonObject
        assertJsonMatches(expected.getValue("notifications"), notifications, "notifications-connected: notifications")
        val idle = Json.parseToJsonElement(idleReply) as JsonObject
        assertJsonMatches(expected, JsonObject(idle + ("notifications" to notifications)), "notifications-connected: the rest of the reply")
        assertEquals(
            Json.parseToJsonElement("""{"state":"unsupported","last_hint_at_ms":null,"hints":0,"expires_at_ms":null,"rotations":0}"""),
            idle.getValue("notifications"),
            "a provider without a channel reports unsupported",
        )
        assertTrue(
            JsonPrimitive(NOTIFICATIONS_STATUS_CAPABILITY) in (idle.getValue("capabilities") as JsonArray),
            "daemon.status names the notifications.status capability",
        )
    }

    /**
     * `events/history.row.ndjson`: the line a `history.subscribe` subscriber is pushed for a transfer the daemon's own
     * history records. Its operation id and instants are the run's, so the fixture's stand in for them (after a check
     * that each is a number) before the comparison.
     * (`events/history.lost.ndjson` is pinned in HistoryRpcHandlerTest, where a subscriber can be made to fall behind.)
     */
    @Test
    fun history_row_event_matches_corpus_over_live_socket() = runBlocking {
        val expectedRow = Json.parseToJsonElement(loadEventLine("history.row")) as JsonObject
        val tempDir = Files.createTempDirectory("ipc-contract-history-event")
        val socketPath = tempDir.resolve("daemon.sock")
        var history: TransferHistory? = null
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            providerHasQuota = false,
        )
        runtime.openHistory = { profile, file -> TransferHistory.open(profile, file).also { history = it } }
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val subscriber = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "contract_profile"), IpcAuth.Scope.READ)
            try {
                subscriber.configureBlocking(false)
                subscriber.write(ByteBuffer.wrap(("""{"verb":"history.subscribe"}""" + "\n").toByteArray()))
                readFirstLine(subscriber, timeoutMs = 5_000)
                val handle = checkNotNull(history).begin(TransferDirection.UPLOAD, "/docs/new.txt", bytesTotal = 10, maxAttempts = 3)
                handle.started()
                handle.finish(TransferResult.SUCCESS, bytesDone = 10)
                // started() and finish() are two snapshots and a batch may merge them: read until the finished one.
                // readFirstLine drops what follows the first line of a read, so this reads whole lines itself.
                val pending = StringBuilder()
                var event: JsonObject
                do {
                    event = Json.parseToJsonElement(readLine(subscriber, pending, timeoutMs = 5_000)) as JsonObject
                } while ((event.getValue("row") as JsonObject).getValue("result").let { (it as JsonPrimitive).content } != "success")
                val row = event.getValue("row") as JsonObject
                assertTrue((row.getValue("operation_id") as JsonPrimitive).content.isNotEmpty())
                assertTrue((event.getValue("seq") as JsonPrimitive).content.toLong() > 0, "a tail event carries a positive commit sequence")
                val expectedInner = expectedRow.getValue("row") as JsonObject
                // The id and the instants are the run's: each must be a number, and the fixture's values stand in for them.
                val runValues = listOf("queued_at_ms", "started_at_ms", "finished_at_ms", "queue_wait_ms", "transfer_ms")
                for (key in runValues) {
                    val value = row.getValue(key)
                    assertTrue(value is JsonPrimitive && !value.isString && value !is JsonNull, "$key is a number, got $value")
                }
                val comparable =
                    JsonObject(
                        event + mapOf(
                            "seq" to expectedRow.getValue("seq"),
                            "row" to JsonObject(
                                row + ("operation_id" to expectedInner.getValue("operation_id")) +
                                    runValues.associateWith { expectedInner.getValue(it) },
                            ),
                        ),
                    )
                assertJsonMatches(expectedRow, comparable, "history.row event")
            } finally {
                subscriber.close()
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    // One transfer row as the history file holds it: the fixtures' rows are these, with the instants they carry.
    @Suppress("LongParameterList") // one parameter per column of a row
    private class HistorySeed(
        val id: Int,
        val direction: String,
        val path: String,
        val queued: Long,
        val started: Long?,
        val finished: Long?,
        val queueWait: Long?,
        val transfer: Long?,
        val bytesTotal: Long?,
        val bytesDone: Long?,
        val result: String,
        val errorClass: String?,
        val errorMessage: String?,
        val attempts: Int?,
        val maxAttempts: Int? = null,
        val nextTryAtMs: Long? = null,
    )

    // The four transfers `history.list.ndjson` is written against.
    private fun seedHistoryCorpus(file: Path) =
        seedHistory(
            file,
            listOf(
                HistorySeed(1, "upload", "/docs/a.txt", 1_700_000_000_000, 1_700_000_000_100, 1_700_000_002_000, 100, 1_900, 100, 100, "success", null, null, 1, 3),
                HistorySeed(2, "download", "/docs/b.txt", 1_700_000_003_000, 1_700_000_003_100, 1_700_000_005_000, 100, 1_900, 2_048, 512, "failed", "network", "connection reset", 3),
                HistorySeed(3, "upload", "/photos/c.jpg", 1_700_000_010_000, 1_700_000_010_100, null, 100, null, 5_000, null, "running", null, null, null, 3),
                HistorySeed(4, "upload", "/docs/d.txt", 1_700_000_019_900, null, 1_700_000_020_000, null, null, 7, null, "skipped", null, null, 0, 3),
            ),
        )

    // The four transfers `sessions/history-retry.ndjson` is written against: an upload waiting in its retry backoff (attempt 2 of 6
    // starts at 1_700_000_400_000), one that used all its attempts, one whose second attempt is under way, and a download.
    private fun seedHistoryRetryCorpus(file: Path) =
        seedHistory(
            file,
            listOf(
                HistorySeed(11, "upload", "/docs/waiting.txt", 1_700_000_100_000, 1_700_000_100_100, null, 100, null, 2_048, null, "running", "SocketTimeoutException", "timed out", 1, 6, 1_700_000_400_000),
                HistorySeed(12, "upload", "/docs/spent.txt", 1_700_000_090_000, 1_700_000_090_100, 1_700_000_099_000, 100, 8_900, 2_048, null, "failed", "SocketTimeoutException", "timed out", 6, 6),
                HistorySeed(13, "upload", "/docs/retrying-now.txt", 1_700_000_080_000, 1_700_000_080_100, null, 100, null, 2_048, null, "running", "SocketTimeoutException", "timed out", 1, 6),
                HistorySeed(14, "download", "/docs/read.txt", 1_700_000_070_000, 1_700_000_070_100, 1_700_000_071_000, 100, 900, 512, 512, "success", null, null, 1),
            ),
        )

    private fun seedHistory(
        file: Path,
        seeds: List<HistorySeed>,
    ) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            c.prepareStatement(
                "INSERT INTO transfers(operation_id, profile, direction, path, queued_at, started_at, finished_at, queue_wait_ms, " +
                    "transfer_ms, bytes_total, bytes_done, result, error_class, error_message, attempts, max_attempts, next_try_at_ms) " +
                    "VALUES (?, 'contract_profile', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                for (s in seeds) {
                    ps.setString(1, "00000000-0000-0000-0000-%012d".format(s.id))
                    ps.setString(2, s.direction)
                    ps.setString(3, s.path)
                    ps.setLong(4, s.queued)
                    ps.setObject(5, s.started)
                    ps.setObject(6, s.finished)
                    ps.setObject(7, s.queueWait)
                    ps.setObject(8, s.transfer)
                    ps.setObject(9, s.bytesTotal)
                    ps.setObject(10, s.bytesDone)
                    ps.setString(11, s.result)
                    ps.setString(12, s.errorClass)
                    ps.setString(13, s.errorMessage)
                    ps.setObject(14, s.attempts)
                    ps.setObject(15, s.maxAttempts)
                    ps.setObject(16, s.nextTryAtMs)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ── corpus loading ───────────────────────────────────────────────────────

    private fun loadConnectionLine(token: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/connection/$token.ndjson")) {
            "missing connection-level corpus fixture '$token'"
        }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        check(lines.size == 1) { "connection/$token.ndjson must hold exactly one line, got ${lines.size}" }
        return lines.single()
    }

    private fun loadSession(name: String): List<Pair<String, String>> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/sessions/$name.ndjson")) {
            "missing session transcript '$name'"
        }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        check(lines.size % 2 == 0) { "sessions/$name.ndjson must hold request/response line pairs, got ${lines.size} lines" }
        return lines.chunked(2).map { it[0] to it[1] }
    }

    private fun loadEventLine(name: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/events/$name.ndjson")) {
            "missing event fixture '$name'"
        }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        check(lines.size == 1) { "events/$name.ndjson must hold exactly one line, got ${lines.size}" }
        return lines.single()
    }

    private fun loadPairs(verb: String): List<Pair<String, String>> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/$verb.ndjson")) {
            "missing corpus fixture for verb '$verb'"
        }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        check(lines.size % 2 == 0) { "$verb.ndjson must hold request/response line pairs, got ${lines.size} lines" }
        return lines.chunked(2).map { it[0] to it[1] }
    }

    // ── parse-and-compare ────────────────────────────────────────────────────

    private fun assertJsonMatches(expected: JsonElement, actual: JsonElement, at: String) {
        when (expected) {
            is JsonNull -> assertEquals(expected, actual, "$at: expected null")
            is JsonObject -> {
                assertTrue(actual is JsonObject, "$at: expected an object, got $actual")
                assertEquals(expected.keys, actual.keys, "$at: key set mismatch")
                for ((key, value) in expected) {
                    val actualValue = actual.getValue(key)
                    if (key in VOLATILE_FIELDS) {
                        assertVolatileShape(value, actualValue, "$at.$key")
                    } else {
                        assertJsonMatches(value, actualValue, "$at.$key")
                    }
                }
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray, "$at: expected an array, got $actual")
                assertEquals(expected.size, actual.size, "$at: array size mismatch")
                expected.forEachIndexed { i, element ->
                    assertJsonMatches(element, actual[i], "$at[$i]")
                }
            }
            is JsonPrimitive -> {
                assertTrue(actual is JsonPrimitive, "$at: expected a primitive, got $actual")
                assertEquals(expected.isString, actual.isString, "$at: string-vs-literal mismatch")
                assertEquals(expected.content, actual.content, "$at: value mismatch")
            }
        }
    }

    // Volatile fields carry run- or machine-dependent values (uuids, uptimes,
    // local cache paths): the corpus value is representative, so pin only
    // presence + JSON type, never the exact bytes.
    private fun assertVolatileShape(expected: JsonElement, actual: JsonElement, at: String) {
        assertTrue(expected is JsonPrimitive && actual is JsonPrimitive, "$at: volatile field must be a primitive")
        assertEquals(expected.isString, actual.isString, "$at: volatile field type mismatch")
    }

    private suspend fun readFirstLine(channel: SocketChannel, timeoutMs: Long): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        val newline = collected.indexOf('\n')
        check(newline >= 0) { "no reply line within ${timeoutMs}ms; collected: $collected" }
        return collected.substring(0, newline)
    }

    /** The next complete line of [channel]; what a read returned beyond it stays in [pending] for the next call. */
    private suspend fun readLine(channel: SocketChannel, pending: StringBuilder, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val newline = pending.indexOf('\n')
            if (newline >= 0) {
                val line = pending.substring(0, newline)
                pending.delete(0, newline + 1)
                return line
            }
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n > 0) pending.append(String(buf.array(), 0, buf.position())) else delay(20)
        }
        error("no line within ${timeoutMs}ms; collected: $pending")
    }

    /**
     * Deterministic Hydration fake scripted by path convention so the real
     * HydrationIpcHandler emits every reply shape the corpus pins:
     * `/missing...` → the unknown_path / *_not_found family, `/docs/sub` →
     * the it's-a-folder family, `/docs/open.txt` → busy, `/docs/nonempty` →
     * not_empty, `/docs/report.txt` → an existing file, `/outside...` →
     * outside_scope (sync_path guard), `/excluded/...` → accepted with
     * excluded:true (keep-local rule), `/Papierkorb...` and `/Recents...` → the read-only trash
     * namespace and the Recents view (every write answers read_only), an open_write cache path outside
     * `/cache/unidrive/hydration` → invalid_path.
     */
    private class ScriptedHydration : Hydration {
        // The four causes of a failed cloud download (archive#941), one token each; the reasons are the daemon's own wording.
        private fun downloadFailureFor(path: String): HydrationError? = when {
            path.startsWith("/gone") -> HydrationError.NotFound
            path.startsWith("/refused") -> HydrationError.ContentRefused("RIPEMD-160(SHA-256) mismatch")
            path.startsWith("/unsupported") ->
                HydrationError.UnsupportedObject("it is stored in several parts, which this client cannot read")
            path.startsWith("/truncated") -> HydrationError.RemoteIncomplete(storedBytes = 298_844_160L, declaredBytes = 360_951_317L)
            else -> null
        }

        override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult = when {
            path.startsWith("/missing") -> OpenResult.Failed(HydrationError.UnknownPath)
            // A cloud download while the daemon is paused: refused at once (archive#778).
            path.startsWith("/paused") -> OpenResult.Failed(HydrationError.Paused)
            downloadFailureFor(path) != null -> OpenResult.Failed(downloadFailureFor(path)!!)
            else -> OpenResult.Ok(cachePathFor(path))
        }

        override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path, baseEtag: String?): OpenResult =
            when {
                // A cache path outside the profile's hydration cache folder is refused before anything else.
                !cachePath.startsWith(cacheDir) -> OpenResult.Failed(HydrationError.InvalidPath)
                readOnly(path) -> OpenResult.Failed(HydrationError.ReadOnly)
                path.startsWith("/missing") -> OpenResult.Failed(HydrationError.UnknownPath)
                // A stale base_etag refuses the write before any upload runs.
                path == "/docs/report.txt" && baseEtag == "stale-etag" -> OpenResult.Failed(HydrationError.Conflict)
                // keep-local: accepted, never uploaded, flagged on the reply.
                path.startsWith("/excluded/") -> OpenResult.Ok(cachePath, excluded = true)
                path.startsWith("/outside") -> OpenResult.Failed(HydrationError.OutOfScope)
                else -> OpenResult.Ok(cachePath)
            }

        override suspend fun closeHandle(connectionId: String, handleId: String) {}

        override suspend fun cancelUpload(path: String): Boolean = path == "/docs/open.txt"

        override suspend fun hydrate(path: String): HydrateResult = when {
            path.startsWith("/missing") -> HydrateResult.Failed(HydrationError.UnknownPath)
            path.startsWith("/paused") -> HydrateResult.Failed(HydrationError.Paused)
            downloadFailureFor(path) != null -> HydrateResult.Failed(downloadFailureFor(path)!!)
            else -> HydrateResult.Ok
        }

        override suspend fun dehydrate(path: String): DehydrateResult = when {
            path.startsWith("/missing") -> DehydrateResult.Failed(HydrationError.UnknownPath)
            path == "/docs/open.txt" -> DehydrateResult.Busy
            else -> DehydrateResult.Ok
        }

        override suspend fun lastSynced(path: String): LastSyncedResult =
            if (path.startsWith("/missing")) LastSyncedResult.Unknown(HydrationError.UNKNOWN_PATH_TOKEN)
            else LastSyncedResult.Ok(FIXED_MTIME_MS)

        // refuse_name (gkrost/unidrive-cli#24): `/cloud...` names a cloud row, `/folder` a folder row, `/nofolder/...` a missing parent;
        // a clear finds a refusal only under `/refused...`.
        override suspend fun refuseName(path: String, sibling: String?): RefuseNameResult =
            when {
                readOnly(path) -> RefuseNameResult.Failed(HydrationError.ReadOnly)
                path.startsWith("/cloud") -> RefuseNameResult.InCloud
                path == "/folder" -> RefuseNameResult.PathIsFolder
                path.startsWith("/nofolder/") -> RefuseNameResult.ParentNotFound
                else -> RefuseNameResult.Ok
            }

        override suspend fun clearNameRefusal(path: String): Boolean = path.startsWith("/refused")

        // matches_local (archive#1219): the proofs the daemon has, one path prefix each; the file name says the local file cannot be read.
        override suspend fun matchesLocal(path: String, localPath: java.nio.file.Path): MatchesLocalResult =
            when {
                path.startsWith("/missing") -> MatchesLocalResult.Unknown(HydrationError.UNKNOWN_PATH_TOKEN)
                localPath.fileName.toString().startsWith("unreadable") -> MatchesLocalResult.Failed(HydrationError.Generic("local_file_unreadable"))
                path.startsWith("/differs") -> MatchesLocalResult.Differs("size")
                path.startsWith("/noproof") -> MatchesLocalResult.Unknown("no_proof")
                path.startsWith("/internxt") -> MatchesLocalResult.Same("internxt-shard-hash")
                else -> MatchesLocalResult.Same("quickxor")
            }

        // view.page: a Recents view whose first page holds one row and whose list goes on; past offset 50 it ends.
        // Every other kind is a view the profile does not show.
        override suspend fun viewPage(kind: String, offset: Int, limit: Int): ViewPageResult {
            if (kind != "recents") return ViewPageResult.UnknownView
            val report =
                ListResult.Entry(
                    "/docs/report.txt", 42, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                    remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-report", etag = "etag-report",
                    pendingUpload = false, hasError = false,
                )
            return if (offset < 50) {
                ViewPageResult.Ok(kind, offset, listOf(report), skipped = 1, nextOffset = offset + limit, state = "ok", asOfMs = 1_700_000_000_000L)
            } else {
                ViewPageResult.Ok(kind, offset, emptyList(), skipped = 0, nextOffset = null, state = "stale", asOfMs = 1_700_000_000_000L)
            }
        }

        override suspend fun list(prefix: String): ListResult =
            if (prefix == "/docs") {
                ListResult.Ok(
                    listOf(
                        // The provider names the stored object the bytes are (gkrost/unidrive-cli#171): `content_id`.
                        ListResult.Entry(
                            "/docs/report.txt", 42, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-report", etag = "etag-report",
                            pendingUpload = false, hasError = false, contentId = "obj-report|42",
                        ),
                        ListResult.Entry(
                            "/docs/sub", 0, FIXED_MTIME_MS, isHydrated = false, isFolder = true,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-sub", etag = null,
                            pendingUpload = false, hasError = false,
                        ),
                        // Written through the mount, upload failed: no remote id / etag /
                        // remote modified time, both flags raised.
                        ListResult.Entry(
                            "/docs/draft.txt", 7, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = null, remoteId = null, etag = null,
                            pendingUpload = true, hasError = true,
                        ),
                        // keep-local: matches exclude_patterns, never uploaded.
                        ListResult.Entry(
                            "/docs/scratch.tmp", 3, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = null, remoteId = null, etag = null,
                            pendingUpload = false, hasError = false, excluded = true,
                        ),
                        // The drive refuses the name (it compares it as equal to a sibling's): never
                        // uploaded, flagged with the reason.
                        ListResult.Entry(
                            "/docs/blocked.txt", 5, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = null, remoteId = null, etag = null,
                            pendingUpload = true, hasError = true, blocked = "similar_name",
                        ),
                    ),
                )
            } else if (prefix == "/") {
                // The top level of a mount with a trash: a live file, and the trash root (its remote_id is the stable key).
                ListResult.Ok(
                    listOf(
                        ListResult.Entry(
                            "/Live.txt", 3, FIXED_MTIME_MS, isHydrated = false, isFolder = false,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-live", etag = "etag-live",
                            pendingUpload = false, hasError = false,
                        ),
                        ListResult.Entry(
                            "/Papierkorb", 0, FIXED_MTIME_MS, isHydrated = false, isFolder = true,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "unidrive:trash", etag = null,
                            pendingUpload = false, hasError = false, readOnly = true, trashRoot = true,
                        ),
                    ),
                )
            } else if (prefix == "/Recents") {
                // A view lists references to real items: its own path and key, the real item's attributes, where it really is.
                fun viewed(name: String, id: String, etag: String, target: String, size: Long = 5, hydrated: Boolean = false) =
                    ListResult.Entry(
                        "/Recents/$name", size, FIXED_MTIME_MS, isHydrated = hydrated, isFolder = false,
                        remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "unidrive:view:recents:$id", etag = etag,
                        pendingUpload = false, hasError = false, readOnly = true, view = "recents", viewTarget = target,
                    )
                ListResult.Ok(
                    listOf(
                        viewed("todo.txt", "22222222-todo", "etag-todo", "/Notes/todo.txt"),
                        viewed("todo (dup-33333333).txt", "33333333-todo", "etag-todo-2", "/Work/todo.txt"),
                        viewed("q3.pdf", "11111111-q3", "etag-q3", "/Work/Reports/q3.pdf", size = 2048, hydrated = true),
                    ),
                )
            } else if (prefix == "/Papierkorb") {
                // Below the root: read_only, and only the root is the root.
                ListResult.Ok(
                    listOf(
                        ListResult.Entry(
                            "/Papierkorb/Old", 0, FIXED_MTIME_MS, isHydrated = false, isFolder = true,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-old", etag = null,
                            pendingUpload = false, hasError = false, readOnly = true,
                        ),
                        ListResult.Entry(
                            "/Papierkorb/note.txt", 9, FIXED_MTIME_MS, isHydrated = false, isFolder = false,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-note", etag = "etag-note",
                            pendingUpload = false, hasError = false, readOnly = true,
                        ),
                    ),
                )
            } else {
                ListResult.Ok(emptyList())
            }

        override suspend fun mkdir(path: String): MkdirResult =
            when {
                readOnly(path) -> MkdirResult.Failed(HydrationError.ReadOnly)
                path.startsWith("/missing/") -> MkdirResult.ParentNotFound
                path.startsWith("/outside") -> MkdirResult.Failed(HydrationError.OutOfScope)
                else -> MkdirResult.Ok
            }

        override suspend fun unlink(path: String, permanent: Boolean): UnlinkResult =
            when {
                readOnly(path) -> UnlinkResult.Failed(HydrationError.ReadOnly)
                path == "/docs/sub" -> UnlinkResult.PathIsFolder
                path == "/docs/uploading.txt" -> UnlinkResult.Busy
                else -> UnlinkResult.Ok
            }

        override suspend fun rmdir(path: String, recursive: Boolean, permanent: Boolean): RmdirResult = when {
            readOnly(path) -> RmdirResult.Failed(HydrationError.ReadOnly)
            path == "/docs/report.txt" -> RmdirResult.PathIsFile
            path == "/docs/nonempty" -> RmdirResult.NotEmpty
            path == "/docs/uploading" -> RmdirResult.Busy
            else -> RmdirResult.Ok
        }

        override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult = when {
            readOnly(path) -> CreateResult.Failed(HydrationError.ReadOnly)
            path.startsWith("/missing/") -> CreateResult.ParentNotFound
            path == "/docs/report.txt" -> CreateResult.PathExists
            // keep-local: the row and cache file exist; the upload never runs.
            path.startsWith("/excluded/") -> CreateResult.Ok(cachePathFor(path), handleId, excluded = true)
            path.startsWith("/outside") -> CreateResult.Failed(HydrationError.OutOfScope)
            else -> CreateResult.Ok(cachePathFor(path), handleId)
        }

        override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult = when {
            readOnly(path) -> OpenResult.Failed(HydrationError.ReadOnly)
            path.startsWith("/missing") -> OpenResult.Failed(HydrationError.UnknownPath)
            path == "/docs/sub" -> OpenResult.Failed(HydrationError.Generic("path_is_folder"))
            path.startsWith("/excluded/") -> OpenResult.Ok(cachePathFor(path), excluded = true)
            path.startsWith("/outside") -> OpenResult.Failed(HydrationError.OutOfScope)
            else -> OpenResult.Ok(cachePathFor(path))
        }

        override suspend fun rename(oldPath: String, newPath: String, replace: Boolean): RenameResult = when {
            // Either end: nothing moves into, out of or within the trash namespace.
            readOnly(oldPath) || readOnly(newPath) -> RenameResult.Failed(HydrationError.ReadOnly)
            oldPath.startsWith("/missing") -> RenameResult.OldPathNotFound
            newPath.startsWith("/missing/") -> RenameResult.NewParentNotFound
            // /docs/sub is a folder: the engine's replace overwrites a file with a file only (HydrationImpl.rename), so a
            // folder destination refuses with or without replace.
            newPath == "/docs/sub" -> RenameResult.NewPathExists
            // An existing file destination refuses without replace; with replace the scripted fake succeeds
            // (the delete-then-move sequence is opaque here).
            newPath == "/docs/existing.txt" && !replace -> RenameResult.NewPathExists
            oldPath.startsWith("/outside") || newPath.startsWith("/outside") -> RenameResult.Failed(HydrationError.OutOfScope)
            newPath.startsWith("/excluded/") -> RenameResult.Failed(HydrationError.Excluded)
            else -> RenameResult.Ok
        }

        override val events: Flow<HydrationEvent> = emptyFlow()

        override fun onConnectionClosed(connectionId: String) {}

        private fun cachePathFor(path: String): Path = Paths.get("/cache/unidrive/hydration$path")

        // The trash namespace and the Recents view: the root folder and everything below it.
        private fun readOnly(path: String): Boolean =
            path == "/Papierkorb" || path.startsWith("/Papierkorb/") || path == "/Recents" || path.startsWith("/Recents/")

        private val cacheDir: Path = Paths.get("/cache/unidrive/hydration")
    }

    /**
     * Minimal CloudProvider stub (mirrors DaemonRuntimeTest's): only
     * authenticateAndLog() plus an empty delta run during refresh.run /
     * sync.enumerate are exercised here.
     */
    private class StubProvider(
        // What the listing reports (empty unless a test puts a trashed item here).
        private val listing: List<CloudItem> = emptyList(),
        // What listView answers per kind; a kind with no entry is one the provider does not have (Unsupported).
        private val viewRefs: Map<ViewKind, List<ViewRef>> = emptyMap(),
        // The kinds the provider declares (Capability.View): those it lists, unless a test declares one it answers Unsupported.
        private val declared: Set<ViewKind> = viewRefs.keys,
    ) : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        // Set by a test to make the next refreshes of a view fail.
        @Volatile
        var viewFailure: Exception? = null

        override fun capabilities(): Set<Capability> = declared.map { Capability.View(it) }.toSet()

        // A list longer than the limit is cut, and the answer says so, as a provider does.
        override suspend fun listView(kind: ViewKind, limit: Int): CapabilityResult<List<ViewRef>> {
            viewFailure?.let { throw it }
            return viewRefs[kind]?.let { CapabilityResult.Success(ViewList(it.take(limit), truncated = it.size > limit)) }
                ?: CapabilityResult.Unsupported(Capability.View(kind), "the stub has no $kind view")
        }

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        // Every object the stub serves has these five bytes.
        override suspend fun download(remotePath: String, destination: Path): Long {
            Files.writeString(destination, "hello")
            return 5L
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = listing, cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    companion object {
        // cache.bytes: the cache size is measured off the request path, so right after the start it is
        // either not measured yet (null) or already measured (0 for the empty corpus daemon); the corpus
        // pins that it is a number-or-null literal, not which of the two a fresh daemon shows.
        private val VOLATILE_FIELDS = setOf("cache_path", "uptime_ms", "clients_connected", "job_id", "engine_version", "engine_build_time_ms", "bytes", "since_ms", "as_of_ms")
        private const val FIXED_MTIME_MS = 1234567890123L
        private const val FIXED_REMOTE_MS = 1234567890000L
    }
}
