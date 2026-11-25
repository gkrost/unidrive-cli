package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.KeyedCredentialProtector
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.testing.FakePlatformProtector
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#797: a sign-in brings fresh credentials, so it replaces a sealed file even when this process holds no key. */
class InternxtSignInOverSealedTest {
    private val dir = TestScratch.directory("internxt-signin-sealed-")
    private val platform = FakePlatformProtector()
    private var current: CredentialSealKey? = null
    private val protector = KeyedCredentialProtector(platform) { current }
    private val file get() = dir.resolve("credentials.json")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun service() =
        object : AuthService(
            config = InternxtConfig(credentialProtector = protector, tokenPath = dir),
            crypto =
                object : InternxtCrypto() {
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

            override suspend fun postLoginAccess(body: JsonObject): String =
                """{"newToken":"jwt-new","user":{"mnemonic":"enc","rootFolderId":"root","uuid":"uuid-ada"}}"""
        }

    @Test
    fun `a sign-in without the key replaces a sealed credentials file`() {
        current = CredentialSealKey(CredentialSealKey.generateSecret(), enabled = true)
        CredentialStore(dir, "credentials.json", InternxtCredentials.serializer(), protector = protector)
            .save(InternxtCredentials(jwt = "old", mnemonic = "old mnemonic", rootFolderId = "root", email = "ada@example.test"))
        assertTrue(Files.readString(file).startsWith(KeyedCredentialProtector.PREFIX))
        current = null

        runBlocking { service().use { it.initializeForSignIn(); it.authenticate("ada@example.test", "pw", null) } }

        assertTrue(Files.readString(file).startsWith(FakePlatformProtector.PREFIX), "platform-only until a keyed process reads it")
        val stored =
            CredentialStore(dir, "credentials.json", InternxtCredentials.serializer(), protector = protector).load()
        assertEquals("jwt-new", stored?.jwt)
    }
}
