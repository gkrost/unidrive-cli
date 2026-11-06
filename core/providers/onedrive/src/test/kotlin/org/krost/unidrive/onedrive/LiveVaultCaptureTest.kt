package org.krost.unidrive.onedrive

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.krost.unidrive.auth.CredentialProtectors
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.onedrive.model.DriveItem
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Personal Vault capture (gkrost/unidrive-cli#405): a gated, read-only recording of the shapes Graph returns for
 * OneDrive Personal Vault, so the provider's stub filter (`DriveItem.isPersonalVault`, archive#332's signature) stays
 * pinned against what a drive really answers, locked or unlocked.
 *
 * Two ways to run it, both hand-triggered, both skipped cleanly otherwise:
 * ```
 * UNIDRIVE_TEST_ACCESS_TOKEN=... ./gradlew :providers:onedrive:test --tests '*LiveVaultCaptureTest'          # a TEST account
 * UNIDRIVE_TEST_PROFILE=<name> UNIDRIVE_TEST_CONFIG_DIR=<dir> ./gradlew :providers:onedrive:test --tests '*LiveVaultCaptureTest'
 * ```
 * The token route matches [LiveGraphIntegrationTest]. The profile route loads the stored token through the engine's own
 * credential store and refreshes it in memory when it is near expiry — the stored `token.json` is never rewritten, and no
 * token ever reaches the output. Set `UNIDRIVE_VAULT_CAPTURE_DIR` to also write the full listings (file names included)
 * to that folder for the account owner's local record; the console output is redacted by design — counts and field
 * shapes only, no names, no ids, no paths — because it is written to be pasted into the public issue.
 *
 * Read-only throughout: `getDelta(readOnly = true)` so the delta-cursor age clock is not reset, and every call is a
 * listing or a metadata read. Nothing is created, changed or deleted.
 *
 * Run it twice for the locked/unlocked comparison (#405 step 1): once with the vault locked (its default state), then
 * right after unlocking it in the OneDrive web UI, and compare the two summaries. Record what changed, if anything, in
 * this class comment when both runs are in.
 */
class LiveVaultCaptureTest {
    private class Shape(item: DriveItem) {
        val hasFolder = item.folder != null
        val hasFile = item.file != null
        val hasPackage = item.packageFacet != null
        val zeroSize = item.size == 0L
        val isTombstone = item.removed != null || item.deleted != null
        val hasParent = item.parentReference != null
        val vaultName = DriveItem.VAULT_NAMES.any { it.equals(item.name, ignoreCase = true) }
        val vaultSignature = item.isPersonalVault
        val stubShape = !hasFolder && !hasFile && !hasPackage && zeroSize && !isTombstone

        // A name-only match is an observation (a real folder/file carrying a vault display name is
        // retained by the provider), never an expected skip: the provider's exclusions follow its
        // actual rules — the vault signature, and the package rule (a package that is not a tombstone).
        val isVaultCandidate = !isTombstone && (vaultName || stubShape)
        val isExpectedSkip = vaultSignature || (hasPackage && !isTombstone)
    }

    private lateinit var service: GraphApiService
    private lateinit var provider: OneDriveProvider

    @BeforeTest
    fun setUp() =
        runBlocking {
        val accessToken = System.getenv("UNIDRIVE_TEST_ACCESS_TOKEN")
        val profile = System.getenv("UNIDRIVE_TEST_PROFILE")
        val configDir = System.getenv("UNIDRIVE_TEST_CONFIG_DIR")
        assumeTrue(
            "set UNIDRIVE_TEST_ACCESS_TOKEN (a TEST account) or UNIDRIVE_TEST_PROFILE + UNIDRIVE_TEST_CONFIG_DIR (the account owner's own capture)",
            !accessToken.isNullOrBlank() || (!profile.isNullOrBlank() && !configDir.isNullOrBlank()),
        )
        val protector = CredentialProtectors.atRestDefaultOrNull()
        val token =
            if (!accessToken.isNullOrBlank()) {
                Token(accessToken = accessToken, tokenType = "Bearer", expiresAt = Long.MAX_VALUE)
            } else {
                val profileDir = Paths.get(configDir).resolve(profile)
                // Read-only on purpose (gkrost/unidrive-cli#407 review): a diagnostic must not
                // rewrite the profile's token.json (the migration a plain load() would run) or
                // touch its permissions as a side effect of reading it.
                val stored =
                    CredentialStore(profileDir, "token.json", Token.serializer(), protector = protector).load(migrate = false)
                        ?: throw AssertionError("the profile holds no stored token; sign in once first")
                if (System.currentTimeMillis() >= stored.expiresAt - 60_000)
                    OAuthService(OneDriveConfig(tokenPath = profileDir, credentialProtector = protector)).refreshToken(
                        stored.refreshToken ?: throw AssertionError("the stored token carries no refresh token"),
                    )
                else stored
            }
        // The tokenProvider lambda answers from the in-memory token only; nothing is written back to the profile.
        service = GraphApiService(OneDriveConfig()) { _, _ -> token.accessToken }
        provider = OneDriveProvider(graphApi = service)
        }

    @AfterTest
    fun tearDown() {
        if (this::service.isInitialized) service.close()
    }

    @Test
    fun `capture the drive's vault shape from the raw listings`() =
        runTest {
            val drive = service.getDrive()
            println("vault-capture: drive type=${drive.driveType}")

            // The root listing, raw: this is where the stub shows (or does not).
            val raw = service.listChildren("/")
            val rawShapes = raw.map { Shape(it) }
            summarize("root/children", rawShapes)

            // The delta walk, capped; a stub the listings carry should surface here too, unchanged by the lock.
            // Entries that fail to decode are counted (they are never classified): a walk with decode skips is
            // reported as incomplete, because a moved vault shape could hide inside them (gkrost/unidrive-cli#407 review).
            var link: String? = null
            val deltaShapes = mutableListOf<Shape>()
            var deltaPages = 0
            var deltaDecodeSkips = 0
            var deltaComplete = false
            while (deltaPages < 100) {
                val result = service.getDelta(link, fromLatest = false, readOnly = true)
                deltaShapes += result.items.map { Shape(it) }
                deltaDecodeSkips += result.skippedIds.size + result.skippedWithoutId
                deltaPages++
                link = result.nextLink
                if (link == null) {
                    deltaComplete = true
                    break
                }
            }
            val deltaLabel =
                when {
                    !deltaComplete -> "capped at $deltaPages pages, INCOMPLETE"
                    deltaDecodeSkips > 0 -> "$deltaPages pages, $deltaDecodeSkips entries failed to decode, INCOMPLETE"
                    else -> "$deltaPages pages, fully decoded, COMPLETE"
                }
            summarize("delta ($deltaLabel)", deltaShapes)
            println("vault-capture: delta decode skips = $deltaDecodeSkips (vault absence is inconclusive while any entry failed to decode)")

            // The provider's own filter, end to end: what the engine really sees of this drive.
            // CloudItem has no Graph facets, so the filter's proof is the count: the engine's listing is
            // the raw one minus the entries the provider's own rules skip (its vault signature and its
            // package rule). A vault CANDIDATE that those rules retain (a real folder carrying a vault
            // display name) stays visible, on purpose (gkrost/unidrive-cli#407 review).
            val filtered = provider.listChildren("/")
            val expectedSkips = rawShapes.count { it.isExpectedSkip }
            val candidates = rawShapes.count { it.isVaultCandidate }
            assertEquals(raw.size - expectedSkips, filtered.size, "the engine's listing is the raw one minus the entries the provider's rules skip")
            println("vault-capture: provider filter held (raw ${raw.size} entries -> engine ${filtered.size}, skipped $expectedSkips, vault candidates observed $candidates)")

            // The owner's local record, file names included: never printed, only written when asked for.
            System.getenv("UNIDRIVE_VAULT_CAPTURE_DIR")?.let { capturePath ->
                val dir = Files.createDirectories(Paths.get(capturePath))
                val json = Json { prettyPrint = true }
                Files.writeString(dir.resolve("root-children.json"), json.encodeToString(ListSerializer(DriveItem.serializer()), raw))
                println("vault-capture: the full listing (with names) went to $dir; do not paste it into any public surface")
            }
        }

    private fun summarize(
        source: String,
        shapes: List<Shape>,
    ) {
        val vaultEntries = shapes.filter { it.isVaultCandidate }
        println(
            "vault-capture: $source — ${shapes.size} entries; folders=${shapes.count { it.hasFolder }} files=${shapes.count { it.hasFile }} " +
                "packages=${shapes.count { it.hasPackage }} tombstones=${shapes.count { it.isTombstone }} " +
                "facet-less-zero-size-non-tombstone=${shapes.count { it.stubShape }} " +
                "vault-name-matches=${shapes.count { it.vaultName && !it.isTombstone }} vault-entries=${vaultEntries.size}",
        )
        for (entry in vaultEntries.take(5)) {
            println(
                "vault-capture:   entry shape: folder=${entry.hasFolder} file=${entry.hasFile} size0=${entry.zeroSize} " +
                    "localeName=${entry.vaultName} parent=${entry.hasParent} signature=${entry.vaultSignature}",
            )
        }
        if (vaultEntries.isEmpty()) println("vault-capture:   no vault entry in $source — the drive may have no vault, or the shape moved")
    }
}
