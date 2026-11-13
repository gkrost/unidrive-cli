package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A provider that cannot tell its quota (OneDrive answers a drive without one) fails `quota()`. The status audit
 * prints the quota as unknown for that; it must not die on it. A failed sign-in still stops the audit.
 */
class StatusAuditQuotaTest {
    private class QuotaProvider(
        val quotaCall: suspend () -> QuotaInfo,
    ) : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = setOf(Capability.QuotaExact)

        override suspend fun authenticate() {}

        override suspend fun quota(): QuotaInfo = quotaCall()

        override fun close() {}

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = error("not used")
    }

    @Test
    fun `an unknown quota is no quota in the audit, not a failure`() {
        val provider = QuotaProvider { throw ProviderException("OneDrive answered no quota for this drive; the quota is unknown") }
        assertNull(runBlocking { StatusAudit.quotaOrNull(provider) })
    }

    @Test
    fun `a known quota is passed on`() {
        val quota = QuotaInfo(total = 10, used = 4, remaining = 6)
        assertEquals(quota, runBlocking { StatusAudit.quotaOrNull(QuotaProvider { quota }) })
    }

    @Test
    fun `a failed sign-in is not hidden as an unknown quota`() {
        val provider = QuotaProvider { throw AuthenticationException("token refused") }
        assertFailsWith<AuthenticationException> { runBlocking { StatusAudit.quotaOrNull(provider) } }
    }
}
