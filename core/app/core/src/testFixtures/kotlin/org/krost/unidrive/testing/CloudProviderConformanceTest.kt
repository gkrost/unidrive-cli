package org.krost.unidrive.testing

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.UnsupportedCapabilityException
import org.krost.unidrive.ViewKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The provider SPI's stated contracts, run against every adapter that implements [CloudProvider]. Each provider
 * module subclasses this and wires its own fake backend (or, for localfs, the real provider over a directory), so a
 * contract that drifts for one provider fails that provider's own build, not just the engine's.
 *
 * The suite pins the safety-relevant decisions each adapter declares through the knobs below: whether the
 * id-addressed writes decide by remote id or keep the documented path fallback, whether a stale `ifMatchETag`
 * refuses or is ignored, whether the stored credentials are a removable file at all. A knob is a decision, not an
 * excuse: the contract runs on both sides of it and pins whichever side the module declares.
 *
 * Two kinds of evidence exist, and a module picks per backend:
 *  - a stateful backend (real state behind the fake): the contract observes the outcome through the provider itself;
 *  - a scripted backend (pre-scripted HTTP answers): the contract observes the recorded request instead, through
 *    [recordedDeleteTargets] / [recordedMoveSources], and asserts the write was addressed by id, never by the path.
 */
abstract class CloudProviderConformanceTest {
    /** A fresh provider over a fresh fake: its own backend state, its own recorded requests, nothing shared. */
    protected abstract fun newProvider(): CloudProvider

    /** The provider for the write contracts, when the module wires writes over a different fake than the rest. */
    protected open fun newWriteProvider(): CloudProvider = newProvider()

    /**
     * The provider for the `ifMatchETag` guard contracts, when the module emulates conditional writes on a fake of
     * its own: a stateful fake without a conditional write cannot answer a stale token with the refusal the contract
     * pins, and a scripted one that answers 412 on a mismatching `If-Match` can.
     */
    protected open fun newGuardProvider(): CloudProvider = newWriteProvider()

    /**
     * Seeds one file at [path] with [content], and scripts whatever the backend must answer for the write contracts
     * that follow (metadata lookups, deletes, moves). Returns the item as the provider sees it.
     */
    protected abstract suspend fun seedFile(
        provider: CloudProvider,
        path: String,
        content: String,
    ): CloudItem

    /** A local file with [content], for the upload contracts. */
    protected abstract fun localFile(content: String): Path

    // ── the module's declared decisions ──────────────────────────────────────

    /** True when the fake holds real state, so the contracts observe outcomes through the provider. */
    protected open val statefulBackend: Boolean = true

    /** True when [CloudProvider.deleteById] decides by remote id; false pins the documented path fallback (ids are the paths). */
    protected open val idAddressedDelete: Boolean = true

    /** True when [CloudProvider.moveById] decides by remote id; false pins the documented path fallback (ids are the paths). */
    protected open val idAddressedMove: Boolean = true

    /** True when [CloudProvider.upload] refuses a stale `ifMatchETag`; false pins the declared ignore. */
    protected open val guardedUpload: Boolean = true

    /** True when [CloudProvider.delete] refuses a stale `ifMatchETag`; false pins the declared ignore. */
    protected open val guardedDelete: Boolean = true

    /** True when the stored credentials are a removable file; false: the profile config is the credential, nothing to remove. */
    protected open val storedCredentialsRemovable: Boolean = false

    /** A token that is not the item's current one, in the shape the provider takes write tokens in. */
    protected open fun staleWriteToken(): String = "conformance-stale-token"

    // ── evidence hooks for a scripted backend ────────────────────────────────

    /** The targets the id-addressed deletes addressed, as the backend recorded them; null when it records nothing. */
    protected open fun recordedDeleteTargets(): List<String>? = null

    /** The sources the id-addressed moves addressed, as the backend recorded them; null when it records nothing. */
    protected open fun recordedMoveSources(): List<String>? = null

    /** Removes the stored credentials (and only the stored ones), so nothing is left to reload. */
    protected open fun clearStoredCredentials() {}

    // ── the contracts ─────────────────────────────────────────────────────────

    @Test
    fun `capabilities declares at least one capability`() {
        val provider = newProvider()
        try {
            val caps = provider.capabilities()
            assertTrue(caps.isNotEmpty(), "capabilities() is how the SPI tells features from silences; an empty set declares nothing")
        } finally {
            provider.close()
        }
    }

    @Test
    fun `optional methods answer Unsupported for capabilities the provider does not declare`() =
        runBlocking<Unit> {
            val provider = newProvider()
            try {
                val caps = provider.capabilities()
                if (Capability.Share !in caps) assertUnsupported(provider.share("/conformance.txt"), Capability.Share)
                if (Capability.ListShares !in caps) assertUnsupported(provider.listShares("/conformance.txt"), Capability.ListShares)
                if (Capability.RevokeShare !in caps) assertUnsupported(provider.revokeShare("/conformance.txt", "share-1"), Capability.RevokeShare)
                if (Capability.VerifyItem !in caps) assertUnsupported(provider.verifyItemExists("conformance-id"), Capability.VerifyItem)
                if (Capability.DeltaShared !in caps) assertUnsupported(provider.deltaWithShared(null), Capability.DeltaShared)
                if (Capability.FastBootstrap !in caps) assertUnsupported(provider.deltaFromLatest(), Capability.FastBootstrap)
                for (kind in ViewKind.entries) {
                    if (Capability.View(kind) !in caps) assertUnsupported(provider.listView(kind, 10), Capability.View(kind))
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `destructive refusals are typed exactly when the capability is undeclared`() =
        runBlocking<Unit> {
            val provider = newProvider()
            try {
                val caps = provider.capabilities()
                if (Capability.PermanentDelete !in caps) {
                    assertFailsWith<UnsupportedCapabilityException>("deletePermanently refuses typed when undeclared") {
                        provider.deletePermanently("/conformance-gone.txt")
                    }
                }
                if (Capability.RemoteTrash !in caps) {
                    assertFailsWith<UnsupportedCapabilityException>("listTrash refuses typed when undeclared") { provider.listTrash() }
                    assertFailsWith<UnsupportedCapabilityException>("emptyTrash refuses typed when undeclared") { provider.emptyTrash() }
                    assertFailsWith<UnsupportedCapabilityException>("purgeTrash refuses typed when undeclared") { provider.purgeTrash(emptyList()) }
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `reloadCredentials picks up the stored credentials`() =
        runBlocking<Unit> {
            val provider = newProvider()
            try {
                provider.reloadCredentials()
                assertTrue(provider.isAuthenticated, "reload left the provider unauthenticated although credentials are stored")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `reloadCredentials with nothing stored leaves the provider unauthenticated`() {
        runBlocking<Unit> {
            // Not every provider stores credentials in a removable file: S3's keys and localfs's root are the profile
            // config itself. For those the module declares `storedCredentialsRemovable = false` and this contract is
            // vacuous — the reload decision it pins is already covered by the pick-up contract above.
            if (!storedCredentialsRemovable) return@runBlocking
            val provider = newProvider()
            try {
                clearStoredCredentials()
                provider.reloadCredentials()
                assertFalse(
                    provider.isAuthenticated,
                    "reload ended authenticated although nothing is stored — it must read what is stored only, never prompt",
                )
            } finally {
                provider.close()
            }
        }
    }

    @Test
    fun `deleteById removes the item the id names, not the item at the handed path`() =
        runBlocking<Unit> {
            val provider = newWriteProvider()
            try {
                val a = seedFile(provider, "/conformance-a.txt", "aaa")
                val b = seedFile(provider, "/conformance-b.txt", "bbb")

                provider.deleteById(b.id, a.path)

                if (statefulBackend) {
                    if (idAddressedDelete) {
                        provider.getMetadata(a.path) // the item at the handed path survives
                        assertFails("the item the id names is gone") { provider.getMetadata(b.path) }
                    } else {
                        // ids are the paths here: the documented fallback decides by the path, and this pins it.
                        assertFails("the fallback deleted the item at the handed path") { provider.getMetadata(a.path) }
                        provider.getMetadata(b.path) // the other item survives
                    }
                } else {
                    val targets = assertNotNull(recordedDeleteTargets(), "a scripted backend must record the delete targets")
                    assertTrue(targets.contains(b.id), "the delete went to the item the id names (targets: $targets)")
                    assertFalse(targets.contains(a.path), "the delete must not resolve the handed path (targets: $targets)")
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `moveById moves the item the id names, not the item at the handed path`() =
        runBlocking<Unit> {
            val provider = newWriteProvider()
            try {
                val a = seedFile(provider, "/conformance-a.txt", "aaa")
                val b = seedFile(provider, "/conformance-b.txt", "bbb")
                val movedPath = "/conformance-moved.txt"

                provider.moveById(b.id, a.path, movedPath)

                if (statefulBackend) {
                    if (idAddressedMove) {
                        provider.getMetadata(a.path) // the item at the handed path survives
                        assertFails("the item the id names left its old path") { provider.getMetadata(b.path) }
                        assertEquals(b.id, provider.getMetadata(movedPath).id, "the item the id names is the one at the new path")
                    } else {
                        // ids are the paths here (they change as the item moves): the documented fallback decides by
                        // the path, and this pins it — the handed path is emptied, the id's item stays, the target is.
                        provider.getMetadata(movedPath)
                        assertFails("the fallback emptied the handed path") { provider.getMetadata(a.path) }
                        provider.getMetadata(b.path) // the other item survives
                    }
                } else {
                    val sources = assertNotNull(recordedMoveSources(), "a scripted backend must record the move sources")
                    assertTrue(sources.contains(b.id), "the move went to the item the id names (sources: $sources)")
                    assertFalse(sources.contains(a.path), "the move must not resolve the handed path (sources: $sources)")
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `upload honours the declared ifMatchETag semantics`() =
        runBlocking<Unit> {
            val provider = newGuardProvider()
            try {
                val item = seedFile(provider, "/conformance-a.txt", "original")
                val local = localFile("replaced")
                if (guardedUpload) {
                    assertFails("a stale ifMatchETag must refuse the write instead of blind-overwriting") {
                        provider.upload(local, item.path, existingRemoteId = item.id, ifMatchETag = staleWriteToken())
                    }
                } else {
                    // The declared decision: a replace ignores the token (a filesystem copy has none to check).
                    provider.upload(local, item.path, existingRemoteId = item.id, ifMatchETag = staleWriteToken())
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `delete honours the declared ifMatchETag semantics`() =
        runBlocking<Unit> {
            val provider = newGuardProvider()
            try {
                val item = seedFile(provider, "/conformance-a.txt", "content")
                if (guardedDelete) {
                    assertFails("a stale ifMatchETag must refuse the delete") {
                        provider.delete(item.path, staleWriteToken())
                    }
                } else {
                    // The declared decision: routine deletes accept and ignore the token.
                    provider.delete(item.path, staleWriteToken())
                }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a full-listing delta reports deletions by omission`() =
        runBlocking<Unit> {
            val provider = newWriteProvider()
            try {
                // Only a provider whose every delta lists the WHOLE remote tree is on the hook here; the rest report
                // deletions as tombstones, and their delta tests are their own.
                if (!provider.deltaIsFullListing) return@runBlocking
                val a = seedFile(provider, "/conformance-a.txt", "aaa")
                val b = seedFile(provider, "/conformance-b.txt", "bbb")

                val first = provider.delta(null)
                assertTrue(first.items.any { it.path == a.path }, "the full listing shows the first item")
                assertTrue(first.items.any { it.path == b.path }, "the full listing shows the second item")

                provider.delete(b.path, null)

                val second = provider.delta(first.cursor)
                assertTrue(second.items.none { it.path == b.path }, "a full-listing delta must report the deletion by omission")
                assertTrue(second.items.any { it.path == a.path }, "the surviving item is still listed")
            } finally {
                provider.close()
            }
        }

    private fun assertUnsupported(
        result: CapabilityResult<*>,
        capability: Capability,
    ) {
        val unsupported = assertIs<CapabilityResult.Unsupported>(result, "an undeclared capability must answer Unsupported, not succeed")
        assertEquals(capability, unsupported.capability, "the Unsupported answer must name the capability it refuses")
    }
}
