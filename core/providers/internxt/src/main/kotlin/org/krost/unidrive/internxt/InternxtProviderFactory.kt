package org.krost.unidrive.internxt

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.JwtExtractor
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path

class InternxtProviderFactory(
    private val credentialProtector: CredentialProtector? = internxtCredentialProtector(),
) : ProviderFactory {
    override val id = "internxt"

    override val metadata =
        ProviderMetadata(
            id = "internxt",
            displayName = "Internxt Drive",
            description = "Privacy-first cloud storage with client-side AES-256 encryption",
            authType = "Email + password",
            encryption = "Client-side AES-256 (file content CTR with SHA-256 integrity check, names GCM)",
            jurisdiction = "EU (Spain)",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = "https://internxt.com",
            tier = "EU-hosted",
            userRating = 4.3,
            benchmarkGrade = "C",
            // archive#457: the wizard for a NEW profile prints this ("no account yet?"); a sign-up through it credits the project.
            affiliateUrl = "https://internxt.cello.so/wboSPv4MM19",
            // gkrost/unidrive-cli#413: 4 uploads at once. The official clients run 4 (drive-desktop), 6 (drive-web) and
            // 10 (internxt/cli); the IV hazards are per file and guarded (retry IV pinning, the resume content
            // fingerprint). Live check of 2026-11-22: 326 files, SHA-256 identical at 2, 4 and 8, no 429 or
            // finishUpload 409, resume after kill -9 held, 1.60x the throughput at 4 over 2.
            maxConcurrentTransfers = 4,
            // gkrost/unidrive-cli#392: a download is three serial round trips (Drive file meta, Bridge file info, the
            // shard GET) and the upload's IV-pinning hazard above does not apply to it. None of them holds a permit of
            // the service's budgets: the Drive meta call is paced only (no spacing until a throttle, then 500 ms, 1 s in
            // a storm) and the Bridge budget is pace-only too. The bench of 2026-11-05 ran 4 at once with no throttle;
            // the reference client does about 10 small files a second. 8 small ones at once.
            maxConcurrentSmallDownloads = 8,
            // gkrost/unidrive-cli#392: 4 is the Bridge budget's audited width (InternxtApiService's bridgeBudget,
            // maxConcurrency 4, the reference client's Bottleneck). So 4 at once; a throttle narrows every download to
            // maxConcurrentTransfers (also 4) for 60 s, which only cuts the small ones from 8 to 4.
            maxConcurrentLargeDownloads = 4,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        // Destructive-overwrite guard: opt-in per profile via
        // `[providers.<name>] keep_overwritten = true`. ProfileResolver
        // funnels TOML keys through the properties map.
        val keepOverwritten = properties["keep_overwritten"]?.toBooleanStrictOrNull() ?: false
        // archive#578: the multipart upload policy keys, per profile. A malformed or
        // out-of-range value is refused loudly — silently falling back to the
        // default would make a typo'd key change nothing and hide why.
        fun intKey(
            key: String,
            default: Int,
            check: (Int) -> String?,
        ): Int =
            properties[key]?.let { raw ->
                val parsed =
                    raw.trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Profile key '$key = $raw' is not an integer")
                check(parsed)?.let { message -> throw IllegalArgumentException(message) }
                parsed
            } ?: default
        val config =
            InternxtConfig(
                tokenPath = tokenPath,
                keepOverwritten = keepOverwritten,
                allowUnverifiedShardHash = properties["allow_unverified_shard_hash"]?.toBooleanStrictOrNull() ?: false,
                strictNames = properties["internxt_strict_names"]?.toBooleanStrictOrNull() ?: false,
                uploadConcurrency =
                    intKey("upload_concurrency", InternxtConfig.DEFAULT_UPLOAD_CONCURRENCY) {
                        if (it !in 1..InternxtConfig.MAX_UPLOAD_CONCURRENCY) {
                            "Profile key 'upload_concurrency = $it' is out of range; expected 1..${InternxtConfig.MAX_UPLOAD_CONCURRENCY}"
                        } else {
                            null
                        }
                    },
                uploadChunkMb =
                    intKey("upload_chunk_mb", InternxtConfig.DEFAULT_UPLOAD_CHUNK_MB) {
                        if (it < InternxtConfig.MIN_UPLOAD_CHUNK_MB) {
                            "Profile key 'upload_chunk_mb = $it' is below the bridge minimum of ${InternxtConfig.MIN_UPLOAD_CHUNK_MB} MB"
                        } else {
                            null
                        }
                    },
                uploadCutoffMb =
                    intKey("upload_cutoff_mb", InternxtConfig.DEFAULT_UPLOAD_CUTOFF_MB) {
                        if (it < InternxtConfig.MIN_UPLOAD_CUTOFF_MB) {
                            "Profile key 'upload_cutoff_mb = $it' is below the multipart minimum of ${InternxtConfig.MIN_UPLOAD_CUTOFF_MB} MB"
                        } else {
                            null
                        }
                    },
                credentialProtector = credentialProtector,
            )
        return InternxtProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = Files.exists(profileDir.resolve("credentials.json"))

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        if (!Files.exists(profileDir.resolve("credentials.json"))) return "internxt (no credentials)"
        return try {
            val credentials = credentialStore(profileDir).load() ?: return "internxt (credentials unreadable)"
            "internxt (${credentials.email})"
        } catch (e: CredentialProtectionException) {
            "internxt (credentials unavailable: ${e.message})"
        } catch (_: Exception) {
            "internxt (credentials unreadable)"
        }
    }

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val credFile = profileDir.resolve("credentials.json")
        if (!Files.exists(credFile)) {
            return CredentialHealth.Missing("No credentials file")
        }

        return try {
            val creds = credentialStore(profileDir).load() ?: return CredentialHealth.Missing("Invalid credentials")
            // UD-010: JWT body decode + exp extraction lifted to JwtExtractor.
            // UD-308 padding fix is preserved in the shared impl.
            val exp = JwtExtractor.extractExp(creds.jwt)
            if (exp != null && System.currentTimeMillis() / 1000 > exp) {
                return CredentialHealth.ExpiresIn(0, "JWT expired")
            }
            CredentialHealth.Ok
        } catch (e: CredentialProtectionException) {
            CredentialHealth.Missing(e.message ?: "Linux Secret Service unavailable")
        } catch (e: Exception) {
            CredentialHealth.Missing("Invalid credentials: ${e.message}")
        }
    }

    private fun credentialStore(profileDir: Path): CredentialStore<InternxtCredentials> =
        CredentialStore(
            profileDir,
            "credentials.json",
            InternxtCredentials.serializer(),
            protector = credentialProtector,
        )
}
