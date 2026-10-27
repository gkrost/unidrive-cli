package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AccountChangedException
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.UnprovenAccountId
import org.krost.unidrive.AccountUnverifiedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sign-in compares the login answer's user uuid with the profile's stored user ([AccountOwner]) before it stores the
 * credentials: another user is refused and the stored credentials stay as they were.
 */
class InternxtAccountOwnerTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("internxt-owner-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun service(uuid: String?): AuthService {
        val user = uuid?.let { ""","uuid":"$it"""" } ?: ""
        return object : AuthService(
            config = InternxtConfig(credentialProtector = null, tokenPath = dir),
            crypto =
                object : InternxtCrypto() {
                    // The sign-in runs on the char-based crypto overloads (issue#83 E).
                    override fun decryptMnemonic(
                        encryptedMnemonicHex: String,
                        password: CharArray,
                    ): String = "mnemonic"

                    override fun hashPassword(
                        password: CharArray,
                        sKey: String,
                        cryptoKey: String,
                    ): String = "hashed"
                },
        ) {
            override suspend fun postLoginChallenge(email: String): String = """{"sKey":"deadbeef","tfa":false}"""

            override suspend fun postLoginAccess(body: kotlinx.serialization.json.JsonObject): String =
                """{"newToken":"${tokenOverride ?: "jwt-of-$email-login"}","user":{"mnemonic":"enc","rootFolderId":"root"$user}}"""
        }
    }

    private var email = "ada@example.test"
    private var tokenOverride: String? = null

    private fun signIn(uuid: String?) =
        runBlocking {
            service(uuid).use {
                it.initialize()
                it.authenticate(email, "pw", null)
            }
        }

    private fun storedCredentials(): String = Files.readString(dir.resolve("credentials.json"))

    @Test
    fun `the first sign-in records the user`() {
        signIn("uuid-ada")

        assertEquals("uuid-ada", AccountOwner.read(dir))
    }

    @Test
    fun `the same user signs in again`() {
        signIn("uuid-ada")
        email = "ada.renamed@example.test"

        signIn("UUID-ADA")

        assertEquals("uuid-ada", AccountOwner.read(dir))
        assertEquals(true, "ada.renamed" in storedCredentials())
    }

    @Test
    fun `another user is refused and nothing is stored`() {
        signIn("uuid-ada")
        val before = storedCredentials()
        email = "bob@example.test"

        assertFailsWith<AccountChangedException> { signIn("uuid-bob") }

        assertEquals(before, storedCredentials(), "the earlier credentials are unchanged")
        assertEquals("uuid-ada", AccountOwner.read(dir))
    }

    @Test
    fun `a profile without a stored user adopts the first sign-in's`() {
        // Signed in before ids were kept: credentials, no account_id.
        signIn(null)
        assertEquals(null, AccountOwner.read(dir))

        signIn("uuid-bob")

        assertEquals("uuid-bob", AccountOwner.read(dir))
    }

    @Test
    fun `a login answer without a uuid is refused on a profile with a stored user`() {
        signIn("uuid-ada")
        val before = storedCredentials()
        email = "bob@example.test"

        assertFailsWith<AccountUnverifiedException> { signIn(null) }

        assertEquals(before, storedCredentials())
    }

    @Test
    fun `a sign-out keeps the stored user`() {
        signIn("uuid-ada")

        runBlocking {
            service(null).use {
                it.initialize()
                it.logout()
            }
        }

        assertFalse(Files.exists(dir.resolve("credentials.json")))
        assertEquals("uuid-ada", AccountOwner.read(dir))
    }

    // ── a profile signed in before ids were kept: the uuid is read from the stored JWT, no network ──

    private fun fakeJwt(payloadJson: String): String =
        "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray()) + ".s"

    private fun uuidOfStoredJwt(payloadJson: String): String? {
        signIn("uuid-ada")
        Files.writeString(
            dir.resolve("credentials.json"),
            Files.readString(dir.resolve("credentials.json")).replace(Regex("\"jwt\":\"[^\"]*\""), "\"jwt\":\"${fakeJwt(payloadJson)}\""),
        )
        return runBlocking {
            service(null).use {
                it.initialize()
                it.currentUserUuid()
            }
        }
    }

    @Test
    fun `the user uuid is read from the nested payload claim of the stored token`() {
        assertEquals("uuid-ada", uuidOfStoredJwt("""{"payload":{"uuid":"uuid-ada","workspaces":{}},"iat":1,"exp":2}"""))
    }

    @Test
    fun `a top-level uuid claim is read too`() {
        assertEquals("uuid-ada", uuidOfStoredJwt("""{"uuid":"uuid-ada"}"""))
    }

    @Test
    fun `a token without a uuid claim gives none`() {
        assertEquals(null, uuidOfStoredJwt("""{"email":"x","iat":1}"""))
    }

    // ── the id recorded at start-up from the token's claim is UNPROVEN until a sign-in proves it ──

    private fun claimJwt(uuid: String) = fakeJwt("""{"payload":{"uuid":"$uuid"}}""")

    /** A profile signed in before ids were kept, whose stored token carries [claim]; start-up has recorded the claim. */
    private fun profileWithStartUpId(claim: String) {
        signIn("uuid-ada")
        Files.delete(dir.resolve("account_id"))
        Files.writeString(
            dir.resolve("credentials.json"),
            Files.readString(dir.resolve("credentials.json")).replace(Regex("\"jwt\":\"[^\"]*\""), "\"jwt\":\"${claimJwt(claim)}\""),
        )
        val recorded = runBlocking { service(null).use { it.initialize(); AccountOwner.recordIfMissing(dir) { it.currentUserUuid() } } }
        assertTrue(recorded)
    }

    @Test
    fun `start-up records the claim and marks it unproven`() {
        profileWithStartUpId("claim-ada")

        assertEquals("claim-ada", AccountOwner.read(dir))
        assertEquals("claim-ada", UnprovenAccountId.id(dir))
    }

    @Test
    fun `a token without the claim records nothing at start-up`() {
        assertEquals(null, uuidOfStoredJwt("""{"email":"x"}"""))
        assertEquals(null, UnprovenAccountId.id(dir))
    }

    @Test
    fun `unproven id and the same user whose claim is not the user uuid is accepted and the id becomes the user uuid`() {
        profileWithStartUpId("claim-ada")
        tokenOverride = claimJwt("claim-ada")

        signIn("uuid-ada")

        assertEquals("uuid-ada", AccountOwner.read(dir))
        assertEquals(null, UnprovenAccountId.id(dir))
        assertTrue(Files.exists(dir.resolve("credentials.json")))
    }

    @Test
    fun `unproven id and another user is account_changed and stores nothing`() {
        profileWithStartUpId("claim-ada")
        val before = storedCredentials()
        tokenOverride = claimJwt("claim-eve")

        assertFailsWith<AccountChangedException> { signIn("uuid-eve") }

        assertEquals(before, storedCredentials())
        assertEquals("claim-ada", AccountOwner.read(dir))
        assertEquals("claim-ada", UnprovenAccountId.id(dir))
    }

    @Test
    fun `unproven id and a token without the claim is account_unverified`() {
        profileWithStartUpId("claim-ada")

        assertFailsWith<AccountUnverifiedException> { signIn("uuid-ada") }

        assertEquals("claim-ada", AccountOwner.read(dir))
    }

    @Test
    fun `a proven id compares the login answer's user uuid, a claim that disagrees never refuses the owner`() {
        signIn("uuid-ada")
        tokenOverride = claimJwt("something-else")

        signIn("uuid-ada")

        assertEquals("uuid-ada", AccountOwner.read(dir))
        assertFailsWith<AccountChangedException> { signIn("uuid-eve") }
    }

    @Test
    fun `a first sign-in where claim and user uuid disagree is stored with the user uuid`() {
        tokenOverride = claimJwt("something-else")

        signIn("uuid-ada")

        assertEquals("uuid-ada", AccountOwner.read(dir))
        assertEquals(null, UnprovenAccountId.id(dir))
    }
}
