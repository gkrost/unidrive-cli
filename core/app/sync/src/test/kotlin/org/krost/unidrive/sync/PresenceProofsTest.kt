package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * Presence proofs (gkrost/unidrive-cli#798, docs/dev/specs/ipc-authentication.md section 4a) without a socket:
 * the policy table, the bound single-use nonces, the anchor rules and the capability. One test per invariant;
 * the live-socket replay is `sessions/presence-destructive.ndjson` in IpcContractCorpusTest.
 */
class PresenceProofsTest {
    private lateinit var dir: Path
    private var now = 1_700_000_000_000L
    private var counter = 0
    private val passphrase = "right-pass"

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("presence-proofs-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun proofs(
        policy: PresencePolicy = PresencePolicy.DESTRUCTIVE,
        vault: Boolean = true,
    ) = PresenceProofs(
        anchorFile = dir.resolve(PresenceProofs.ANCHOR_FILE),
        profileName = "p1",
        policy = policy,
        passphraseMatches = if (vault) ({ it.concatToString() == passphrase }) else null,
        nonces = { ByteArray(PresenceProofs.NONCE_BYTES).also { java.nio.ByteBuffer.wrap(it).putInt(++counter) } },
        nowNanos = { now * 1_000_000 },
        failureDelayMs = 0,
    )

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun sign(
        key: KeyPair,
        message: ByteArray,
    ): String =
        IpcAuth.encode(
            Signature.getInstance("SHA256withRSA").run {
                initSign(key.private)
                update(message)
                sign()
            },
        )

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun error(reply: String?): String? = reply?.let { obj(it)["error"]?.jsonPrimitive?.content }

    private fun spki(key: KeyPair): String = IpcAuth.encode(key.public.encoded)

    /** A nonce from presence.challenge for [target] over [params] (the members the proof binds). */
    private fun challenge(
        p: PresenceProofs,
        target: String,
        params: JsonObject,
    ): String {
        val reply = obj(p.challenge("""{"verb":"presence.challenge","target":"$target","params_hash":"${PresenceProofs.paramsHash(params)}"}"""))
        assertEquals(true, reply["ok"]?.jsonPrimitive?.content?.toBoolean(), "challenge refused: $reply")
        return reply.getValue("nonce").jsonPrimitive.content
    }

    /** The `presence` member of a guarded request: a fresh nonce for [target] over [params], signed with [key]. */
    private fun proof(
        p: PresenceProofs,
        key: KeyPair,
        target: String = "auth.logout",
        params: JsonObject = JsonObject(emptyMap()),
    ): String {
        val nonce = challenge(p, target, params)
        val signature = sign(key, PresenceProofs.message("p1", target, PresenceProofs.paramsHash(params), nonce))
        return """{"nonce":"$nonce","signature":"$signature"}"""
    }

    /** A presence.enroll request for [key]: its possession proof, and either the passphrase or the current anchor's proof. */
    private fun enrollRequest(
        p: PresenceProofs,
        key: KeyPair,
        passphrase: String? = null,
        current: KeyPair? = null,
    ): String {
        val params = JsonObject(mapOf("public_key" to JsonPrimitive(spki(key))))
        val nonce = challenge(p, PresenceProofs.ENROLL, params)
        val message = PresenceProofs.message("p1", PresenceProofs.ENROLL, PresenceProofs.paramsHash(params), nonce)
        return buildString {
            append("""{"verb":"presence.enroll","public_key":"${spki(key)}","nonce":"$nonce","signature":"${sign(key, message)}"""")
            if (passphrase != null) append(""","passphrase":"$passphrase"""")
            if (current != null) append(""","anchor_signature":"${sign(current, message)}"""")
            append("}")
        }
    }

    private suspend fun enrolled(
        p: PresenceProofs,
        key: KeyPair,
    ) {
        val reply = obj(p.enroll(enrollRequest(p, key, passphrase = passphrase)))
        assertEquals("true", reply["ok"]?.jsonPrimitive?.content, "first enrollment refused: $reply")
    }

    // ── the policy table ───────────────────────────────────────────────────────────────────────

    @Test
    fun policy_off_guards_no_verb() {
        for (verb in IpcAuth.VERB_CLASSES.keys) assertFalse(PresencePolicy.OFF.guards(verb), verb)
    }

    @Test
    fun policy_destructive_guards_exactly_the_destructive_table() {
        assertEquals(
            IpcAuth.PRESENCE_DESTRUCTIVE_VERBS,
            IpcAuth.VERB_CLASSES.keys.filter { PresencePolicy.DESTRUCTIVE.guards(it) }.toSet(),
        )
        assertEquals(setOf("auth.logout"), IpcAuth.PRESENCE_DESTRUCTIVE_VERBS)
    }

    @Test
    fun policy_admin_guards_every_admin_verb_but_the_presence_verbs() {
        val admin = IpcAuth.VERB_CLASSES.filterValues { it == IpcAuth.VerbClass.ADMIN }.keys - PresenceProofs.VERBS
        assertEquals(admin, IpcAuth.VERB_CLASSES.keys.filter { PresencePolicy.ADMIN.guards(it) }.toSet())
        assertFalse(PresencePolicy.ADMIN.guards("no.such.verb"), "an unregistered verb stays unknown_verb, not presence_required")
    }

    @Test
    fun policy_parse_defaults_to_off_and_refuses_an_unknown_value() {
        assertEquals(PresencePolicy.OFF, PresencePolicy.parse(null))
        assertEquals(PresencePolicy.DESTRUCTIVE, PresencePolicy.parse("destructive"))
        assertEquals(PresencePolicy.ADMIN, PresencePolicy.parse("admin"))
        assertFailsWith<IllegalArgumentException> { PresencePolicy.parse("destructve") }
    }

    @Test
    fun an_unguarded_verb_passes_the_gate_without_a_proof() {
        assertNull(proofs().gate("daemon.status", """{"verb":"daemon.status"}"""))
        assertNull(proofs(PresencePolicy.OFF).gate("auth.logout", """{"verb":"auth.logout"}"""))
    }

    // ── proofs for guarded verbs ───────────────────────────────────────────────────────────────

    @Test
    fun a_guarded_verb_without_an_anchor_is_refused_presence_required() {
        val reply = obj(proofs().gate("auth.logout", """{"verb":"auth.logout"}""")!!)
        assertEquals("presence_required", reply["error"]?.jsonPrimitive?.content)
        assertEquals("false", reply["enrolled"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_guarded_verb_without_a_proof_is_refused_presence_required() =
        runTest {
            val p = proofs()
            enrolled(p, keyPair())
            val reply = obj(p.gate("auth.logout", """{"verb":"auth.logout"}""")!!)
            assertEquals("presence_required", reply["error"]?.jsonPrimitive?.content)
            assertEquals("true", reply["enrolled"]?.jsonPrimitive?.content)
            assertEquals("auth.logout", reply["verb"]?.jsonPrimitive?.content)
        }

    @Test
    fun a_guarded_verb_with_a_valid_proof_passes_the_gate() =
        runTest {
            val p = proofs()
            val key = keyPair()
            enrolled(p, key)
            assertNull(p.gate("auth.logout", """{"verb":"auth.logout","presence":${proof(p, key)}}"""))
        }

    @Test
    fun a_replayed_proof_is_refused_presence_invalid() =
        runTest {
            val p = proofs()
            val key = keyPair()
            enrolled(p, key)
            val line = """{"verb":"auth.logout","presence":${proof(p, key)}}"""
            assertNull(p.gate("auth.logout", line))
            assertEquals("presence_invalid", error(p.gate("auth.logout", line)))
        }

    @Test
    fun a_proof_for_another_verb_is_refused_presence_invalid() =
        runTest {
            val p = proofs(PresencePolicy.ADMIN)
            val key = keyPair()
            enrolled(p, key)
            val forLock = proof(p, key, target = "vault.lock")
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","presence":$forLock}""")))
        }

    @Test
    fun a_proof_with_a_changed_param_is_refused_presence_invalid() =
        runTest {
            val p = proofs()
            val key = keyPair()
            enrolled(p, key)
            val forPlain = proof(p, key)
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","force":true,"presence":$forPlain}""")))
        }

    @Test
    fun a_failed_proof_spends_its_nonce() =
        runTest {
            val p = proofs()
            val key = keyPair()
            enrolled(p, key)
            val good = proof(p, key)
            val nonce = obj(good).getValue("nonce").jsonPrimitive.content
            val bad = """{"nonce":"$nonce","signature":"${IpcAuth.encode(ByteArray(256))}"}"""
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","presence":$bad}""")))
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","presence":$good}""")))
        }

    @Test
    fun an_expired_nonce_is_refused_presence_expired() =
        runTest {
            val p = proofs()
            val key = keyPair()
            enrolled(p, key)
            val line = """{"verb":"auth.logout","presence":${proof(p, key)}}"""
            now += PresenceProofs.NONCE_TTL_MS + 1
            assertEquals("presence_expired", error(p.gate("auth.logout", line)))
        }

    @Test
    fun a_signature_under_another_key_is_refused_presence_invalid() =
        runTest {
            val p = proofs()
            enrolled(p, keyPair())
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","presence":${proof(p, keyPair())}}""")))
        }

    @Test
    fun the_member_order_of_a_request_does_not_change_its_params_hash() {
        val a = obj("""{"b":1,"a":"x","c":{"z":true,"y":null}}""")
        val b = obj("""{"c":{"y":null,"z":true},"a":"x","b":1}""")
        assertEquals(PresenceProofs.paramsHash(a), PresenceProofs.paramsHash(b))
    }

    @Test
    fun the_fixed_vectors_of_the_spec() {
        // docs/dev/specs/ipc-authentication.md section 4a: canonical params, their hash and the signed message.
        assertEquals("""{"force":true}""", PresenceProofs.canonical(obj("""{ "force" : true }""")))
        val sha = IpcAuth.encode(MessageDigest.getInstance("SHA-256").digest("""{"force":true}""".toByteArray()))
        assertEquals(sha, PresenceProofs.paramsHash(obj("""{"force":true}""")))
        assertEquals("RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o", PresenceProofs.paramsHash(JsonObject(emptyMap())))
        val nonce = IpcAuth.encode(ByteArray(32) { 1 })
        assertEquals(
            "unidrive-presence-v1|2:p1|auth.logout|RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o|$nonce",
            PresenceProofs.message("p1", "auth.logout", PresenceProofs.paramsHash(JsonObject(emptyMap())), nonce).decodeToString(),
        )
    }

    @Test
    fun canonical_text_pins_numbers_escapes_repeats_and_member_order() {
        // docs/dev/specs/ipc-authentication.md section 4a, "Test vectors": what a client in another language must reproduce.
        // Numbers keep their literal.
        assertEquals(
            """{"n":[1.0,1e3,-0,123456789012345678901234567890,0.000001]}""",
            PresenceProofs.canonical(obj("""{"n":[1.0,1e3,-0,123456789012345678901234567890,0.000001]}""")),
        )
        // An escape is decoded, and non-ASCII is written as is.
        assertEquals("{\"t\":\"\u00e9\",\"u\":\"\u00e9\"}", PresenceProofs.canonical(obj("""{"t":"\u00e9","u":"\u00e9"}""")))
        // The five short escapes and \u00xx below U+0020; "/", DEL and U+2028 as is.
        assertEquals(
            "{\"s\":\"\u00e9\\u0001/\\\"\\\\\u007f\u2028\"}",
            PresenceProofs.canonical(obj("""{"s":"\u00e9\u0001\/\"\\\u007f\u2028"}""")),
        )
        assertEquals("{\"s\":\"\\b\\f\\n\\r\\t\\u000b\\u001f\"}", PresenceProofs.canonical(obj("""{"s":"\b\f\n\r\t\u000b\u001f"}""")))
        // A repeated member keeps the last.
        assertEquals("""{"a":2}""", PresenceProofs.canonical(obj("""{"a":1,"a":2}""")))
        // Members sort by UTF-16 code unit: a surrogate pair (U+D83D) before U+FF5E, which code point order would reverse.
        assertEquals(
            "{\"s\":1,\"\ud83d\ude00\":2,\"\uff5e\":3}",
            PresenceProofs.canonical(obj("""{"\uff5e":3,"\ud83d\ude00":2,"s":1}""")),
        )
    }

    @Test
    fun a_nonce_expires_by_the_monotonic_clock_alone() =
        runTest {
            var nanos = 5L
            val p =
                PresenceProofs(
                    dir.resolve(PresenceProofs.ANCHOR_FILE), "p1", PresencePolicy.DESTRUCTIVE, { true },
                    nowNanos = { nanos }, failureDelayMs = 0,
                )
            val key = keyPair()
            assertEquals("true", obj(p.enroll(enrollRequest(p, key, passphrase = passphrase)))["ok"]?.jsonPrimitive?.content)
            val line = """{"verb":"auth.logout","presence":${proof(p, key)}}"""
            nanos += (PresenceProofs.NONCE_TTL_MS + 1) * 1_000_000
            assertEquals("presence_expired", error(p.gate("auth.logout", line)))
            val fresh = """{"verb":"auth.logout","presence":${proof(p, key)}}"""
            nanos += (PresenceProofs.NONCE_TTL_MS - 1) * 1_000_000
            assertNull(p.gate("auth.logout", fresh), "just inside the 60 s the nonce still holds")
        }

    @Test
    fun status_json_says_the_policy_and_whether_an_anchor_is_enrolled() =
        runTest {
            val p = proofs(PresencePolicy.ADMIN)
            assertEquals("""{"policy":"admin","enrolled":false}""", p.statusJson())
            enrolled(p, keyPair())
            assertEquals("""{"policy":"admin","enrolled":true}""", p.statusJson())
            Files.deleteIfExists(dir.resolve(PresenceProofs.ANCHOR_FILE))
            assertEquals("""{"policy":"off","enrolled":false}""", proofs(PresencePolicy.OFF).statusJson())
        }

    @Test
    fun a_daemon_without_a_presence_policy_takes_no_enrollment_and_asks_no_passphrase() =
        runTest {
            var checks = 0
            val p =
                PresenceProofs(
                    dir.resolve(PresenceProofs.ANCHOR_FILE), "p1", PresencePolicy.OFF, { checks++ >= 0 },
                    nowNanos = { now * 1_000_000 }, failureDelayMs = 0,
                )
            val reply = p.enroll(enrollRequest(p, keyPair(), passphrase = passphrase))
            assertEquals("presence_disabled", error(reply))
            assertEquals(0, checks, "no co-factor work under policy off")
            assertFalse(p.enrolled)
            assertFalse(Files.exists(dir.resolve(PresenceProofs.ANCHOR_FILE)))
        }

    @Test
    fun a_challenge_with_a_bad_target_or_hash_is_refused() {
        val p = proofs()
        assertEquals("invalid_target", error(p.challenge("""{"verb":"presence.challenge"}""")))
        assertEquals("invalid_target", error(p.challenge("""{"verb":"presence.challenge","target":"a|b"}""")))
        assertEquals("invalid_params_hash", error(p.challenge("""{"verb":"presence.challenge","target":"auth.logout"}""")))
        assertEquals("invalid_params_hash", error(p.challenge("""{"verb":"presence.challenge","target":"auth.logout","params_hash":"AAAA"}""")))
    }

    @Test
    fun outstanding_nonces_are_bounded() =
        runTest {
            val p = proofs()
            val key = keyPair()
            enrolled(p, key)
            val first = proof(p, key)
            repeat(PresenceProofs.MAX_OUTSTANDING) { challenge(p, "auth.logout", JsonObject(emptyMap())) }
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","presence":$first}""")), "the oldest nonce was dropped")
        }

    // ── the anchor ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_first_enrollment_without_the_passphrase_is_refused() =
        runTest {
            val p = proofs()
            assertEquals("passphrase_required", error(p.enroll(enrollRequest(p, keyPair()))))
            assertFalse(p.enrolled)
            assertFalse(Files.exists(dir.resolve(PresenceProofs.ANCHOR_FILE)))
        }

    @Test
    fun the_first_enrollment_with_a_wrong_passphrase_is_refused() =
        runTest {
            val p = proofs()
            assertEquals("vault_bad_passphrase", error(p.enroll(enrollRequest(p, keyPair(), passphrase = "wrong"))))
            assertFalse(p.enrolled)
        }

    @Test
    fun the_first_enrollment_without_a_vault_is_refused() =
        runTest {
            val p = proofs(vault = false)
            assertEquals("vault_absent", error(p.enroll(enrollRequest(p, keyPair(), passphrase = passphrase))))
            assertFalse(p.enrolled)
        }

    @Test
    fun an_enrollment_without_a_possession_proof_of_the_new_key_is_refused() =
        runTest {
            val p = proofs()
            val key = keyPair()
            val params = JsonObject(mapOf("public_key" to JsonPrimitive(spki(key))))
            val nonce = challenge(p, PresenceProofs.ENROLL, params)
            val byOther = sign(keyPair(), PresenceProofs.message("p1", PresenceProofs.ENROLL, PresenceProofs.paramsHash(params), nonce))
            val reply = p.enroll("""{"verb":"presence.enroll","public_key":"${spki(key)}","nonce":"$nonce","signature":"$byOther","passphrase":"$passphrase"}""")
            assertEquals("presence_invalid", error(reply))
            assertEquals("missing_proof", error(p.enroll("""{"verb":"presence.enroll","public_key":"${spki(key)}","passphrase":"$passphrase"}""")))
            assertEquals("invalid_public_key", error(p.enroll("""{"verb":"presence.enroll","public_key":"AAAA"}""")))
            assertFalse(p.enrolled)
        }

    @Test
    fun the_first_enrollment_with_the_passphrase_stores_an_owner_only_anchor() =
        runTest {
            val p = proofs()
            val key = keyPair()
            val reply = obj(p.enroll(enrollRequest(p, key, passphrase = passphrase)))
            assertEquals("false", reply["rotated"]?.jsonPrimitive?.content)
            assertEquals(IpcAuth.encode(MessageDigest.getInstance("SHA-256").digest(key.public.encoded)), reply["anchor"]?.jsonPrimitive?.content)
            assertTrue(p.enrolled)
            val file = dir.resolve(PresenceProofs.ANCHOR_FILE)
            assertEquals(spki(key), Files.readString(file).trim())
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertNull(OwnerOnly.grantProblem(file), "the anchor is owner-only")
            }
            // A new daemon start reads the anchor back and accepts its proofs.
            val restarted = proofs()
            assertTrue(restarted.enrolled)
            assertNull(restarted.gate("auth.logout", """{"verb":"auth.logout","presence":${proof(restarted, key)}}"""))
        }

    @Test
    fun rotation_without_a_proof_under_the_current_anchor_is_refused() =
        runTest {
            val p = proofs()
            val first = keyPair()
            enrolled(p, first)
            // The passphrase alone does not replace an anchor.
            assertEquals("presence_required", error(p.enroll(enrollRequest(p, keyPair(), passphrase = passphrase))))
            // Nor does a proof under another key.
            assertEquals("presence_invalid", error(p.enroll(enrollRequest(p, keyPair(), current = keyPair()))))
            assertNull(p.gate("auth.logout", """{"verb":"auth.logout","presence":${proof(p, first)}}"""), "the first anchor still stands")
        }

    @Test
    fun rotation_with_a_proof_under_the_current_anchor_replaces_it() =
        runTest {
            val p = proofs()
            val first = keyPair()
            val second = keyPair()
            enrolled(p, first)
            assertEquals("true", obj(p.enroll(enrollRequest(p, second, current = first)))["rotated"]?.jsonPrimitive?.content)
            assertEquals("presence_invalid", error(p.gate("auth.logout", """{"verb":"auth.logout","presence":${proof(p, first)}}""")))
            assertNull(p.gate("auth.logout", """{"verb":"auth.logout","presence":${proof(p, second)}}"""))
        }

    @Test
    fun a_corrupt_anchor_file_is_no_anchor() {
        Files.writeString(dir.resolve(PresenceProofs.ANCHOR_FILE), "not a key\n")
        val p = proofs()
        assertFalse(p.enrolled)
        assertEquals("presence_required", error(p.gate("auth.logout", """{"verb":"auth.logout"}""")))
    }

    // ── the capability ─────────────────────────────────────────────────────────────────────────

    @Test
    fun the_capability_is_advertised_only_once_an_anchor_exists() =
        runTest {
            val p = proofs(PresencePolicy.DESTRUCTIVE)
            assertEquals("""["mount"]""", p.withCapability("""["mount"]"""))
            assertEquals("[]", p.withCapability("[]"))
            enrolled(p, keyPair())
            assertEquals("""["mount","user-presence:v1"]""", p.withCapability("""["mount"]"""))
            assertEquals("""["user-presence:v1"]""", p.withCapability("[]"))
        }

    @Test
    fun the_anchor_file_is_refused_when_it_cannot_be_made_owner_only() =
        runTest {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
            val p =
                PresenceProofs(
                    dir.resolve(PresenceProofs.ANCHOR_FILE), "p1", PresencePolicy.DESTRUCTIVE, { true },
                    failureDelayMs = 0, grantProblem = { "group-readable" },
                )
            assertEquals("anchor_write_failed", error(p.enroll(enrollRequest(p, keyPair(), passphrase = passphrase))))
            assertFalse(p.enrolled)
        }
}
