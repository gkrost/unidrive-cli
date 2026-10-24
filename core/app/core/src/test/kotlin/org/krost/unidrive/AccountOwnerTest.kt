package org.krost.unidrive

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stored user of a profile's state ([AccountOwner]): the verdict a sign-in gets before it stores anything. */
class AccountOwnerTest {
    private val dir = Files.createTempDirectory("account-owner")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a profile without a stored user adopts any user, also an unknown one`() {
        assertEquals(AccountOwner.Verdict.ADOPT, AccountOwner.verdict(dir, "id-a"))
        assertEquals(AccountOwner.Verdict.ADOPT, AccountOwner.verdict(dir, null))
        AccountOwner.requireSameUser(dir, "id-a")
    }

    @Test
    fun `the stored user is compared without case, another user and an unknown one are refused`() {
        AccountOwner.record(dir, "AbC-1")

        assertEquals(AccountOwner.Verdict.SAME, AccountOwner.verdict(dir, "abc-1"))
        assertEquals(AccountOwner.Verdict.CHANGED, AccountOwner.verdict(dir, "abc-2"))
        assertEquals(AccountOwner.Verdict.UNVERIFIED, AccountOwner.verdict(dir, " "))
        assertFailsWith<AccountChangedException> { AccountOwner.requireSameUser(dir, "abc-2") }
        assertFailsWith<AccountUnverifiedException> { AccountOwner.requireSameUser(dir, null) }
    }

    @Test
    fun `record never replaces a stored user and ignores a blank one`() {
        AccountOwner.record(dir, " ")
        assertEquals(null, AccountOwner.read(dir))

        AccountOwner.record(dir, "id-a")
        AccountOwner.record(dir, "id-b")

        assertEquals("id-a", AccountOwner.read(dir))
    }

    @Test
    fun `the refusal names no user`() {
        AccountOwner.record(dir, "id-secret-a")

        val e = assertFailsWith<AccountChangedException> { AccountOwner.requireSameUser(dir, "id-secret-b") }

        assertEquals(false, "id-secret" in e.message.orEmpty())
    }

    // ── recordIfMissing: a profile signed in before ids were kept ──────────────────────────────────────────────────

    @Test
    fun `recordIfMissing records the id of the current credentials when none is stored`() =
        runBlocking {
            assertTrue(AccountOwner.recordIfMissing(dir) { "id-a" })

            assertEquals("id-a", AccountOwner.read(dir))
        }

    @Test
    fun `recordIfMissing leaves a stored id alone and makes no identity call`() =
        runBlocking {
            AccountOwner.record(dir, "id-a")
            var calls = 0

            val recorded = AccountOwner.recordIfMissing(dir) { calls++; "id-b" }

            assertFalse(recorded)
            assertEquals(0, calls)
            assertEquals("id-a", AccountOwner.read(dir))
        }

    @Test
    fun `a failing identity call records nothing and does not throw`() =
        runBlocking {
            assertFalse(AccountOwner.recordIfMissing(dir) { throw java.io.IOException("connection reset") })
            assertFalse(AccountOwner.recordIfMissing(dir) { throw AuthenticationException("401") })
            assertFalse(AccountOwner.recordIfMissing(dir) { null })
            assertFalse(AccountOwner.recordIfMissing(dir) { "  " })

            assertNull(AccountOwner.read(dir))
        }

    @Test
    fun `a slow identity call is given up after the bound and records nothing`() =
        runBlocking {
            val recorded =
                AccountOwner.recordIfMissing(dir, timeoutMs = 100) {
                    delay(60_000)
                    "id-a"
                }

            assertFalse(recorded)
            assertNull(AccountOwner.read(dir))
        }

    @Test
    fun `a run records once, a second run in the same profile makes no call`() =
        runBlocking {
            var calls = 0

            AccountOwner.recordIfMissing(dir) { calls++; "id-a" }
            AccountOwner.recordIfMissing(dir) { calls++; "id-a" }

            assertEquals(1, calls)
        }

    @Test
    fun `a provider that is not signed in is not asked`() =
        runBlocking {
            var calls = 0
            val provider = IdentityProvider(signedIn = false, onAsk = { calls++ }, userId = "id-a")

            assertFalse(AccountOwner.recordIfMissing(dir, provider))

            assertEquals(0, calls)
            assertNull(AccountOwner.read(dir))
        }

    @Test
    fun `a provider without a user records nothing`() =
        runBlocking {
            assertFalse(AccountOwner.recordIfMissing(dir, IdentityProvider(signedIn = true, userId = null)))

            assertNull(AccountOwner.read(dir))
        }

    @Test
    fun `a signed in provider is asked and its id recorded`() =
        runBlocking {
            assertTrue(AccountOwner.recordIfMissing(dir, IdentityProvider(signedIn = true, userId = "id-a")))

            assertEquals("id-a", AccountOwner.read(dir))
        }

    private class IdentityProvider(
        signedIn: Boolean,
        private val userId: String?,
        private val onAsk: () -> Unit = {},
    ) : CloudProvider {
        override val id: String get() = "stub"
        override val displayName = "Stub"
        override var isAuthenticated = signedIn

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun currentAccountId(): String? {
            onAsk()
            return userId
        }

        override suspend fun listChildren(path: String): List<CloudItem> = error("unused")

        override suspend fun getMetadata(path: String): CloudItem = error("unused")

        override suspend fun download(
            remotePath: String,
            destination: java.nio.file.Path,
        ): Long = error("unused")

        override suspend fun upload(
            localPath: java.nio.file.Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("unused")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("unused")

        override suspend fun createFolder(path: String): CloudItem = error("unused")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("unused")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("unused")

        override suspend fun quota(): QuotaInfo = error("unused")
    }
}
