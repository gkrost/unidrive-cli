package org.krost.unidrive.auth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.withLocalIoRetryBlocking
import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.nio.file.Files
import java.nio.file.Path

/**
 * UD-344: shared credential-file load / save / delete with the
 * UD-312 atomic-write invariant baked in.
 *
 * Pre-UD-344 OneDrive, HiDrive and Internxt each implemented the same
 * "load from $dir/$file → parse → discard on parse error" + "write
 * → chmod 600" flow. OneDrive added UD-312 (atomic-move + shape
 * guard) on top. HiDrive and Internxt copied the basic flow **without
 * UD-312** — both had a real (small) crash-window race the UD-312
 * OneDrive comment explicitly identifies.
 *
 * Lifted here so:
 *   - HiDrive + Internxt inherit UD-312 atomic-write —
 *     partial-write crashes no longer leave corrupted credential files.
 *   - OneDrive's existing `hasPlausibleAccessTokenShape()` shape
 *     guard wires through the optional [validate] hook.
 *
 * Construction is configuration only; no I/O happens until [load],
 * [save] or [delete] is called.
 */
public class CredentialStore<T>(
    private val dir: Path,
    private val fileName: String,
    private val serializer: KSerializer<T>,
    private val validate: (T) -> Boolean = { true },
    private val protector: CredentialProtector? = null,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(CredentialStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val file: Path get() = dir.resolve(fileName)

    /**
     * Load and parse the credential file. Returns `null` if:
     *   - the file does not exist, or
     *   - parsing throws (treated as corrupt JSON; logged and swallowed
     *     so the caller falls through to a fresh authentication path), or
     *   - [validate] returns `false` (UD-312 lineage — caller-supplied
     *     shape guard for "syntactically parseable but obviously bad",
     *     e.g. empty access_token).
     *
     * Every other failure throws rather than reading as "signed out": a [CredentialProtectionException] and any
     * I/O failure on a file that exists (a lock that outlasts the one retry, a permission error) propagate to the
     * caller (windows#454 item 3). Only a missing file, corrupt content and a failed [validate] are `null`.
     */
    public fun load(): T? = load(migrate = true)

    /**
     * Load and parse like [load], with the legacy-plaintext migration optional. `migrate = false`
     * decodes and validates **without writing**: a diagnostic tool that only reads (the gated live
     * captures, gkrost/unidrive-cli#407 review) must not rewrite the profile's file or change its
     * permissions as a side effect of loading. Ordinary callers keep [load], whose migration removes
     * any plaintext from disk before parsing.
     */
    public fun load(migrate: Boolean): T? {
        if (!Files.exists(file)) return null

        return try {
            // windows#454 item 3: a locked file (an antivirus, a backup tool) is not "not signed in" — it is an I/O
            // failure, and reading it as null sent the daemon into a needless re-sign-in (CredentialProtector's own
            // contract). One retry through the local-I/O lock window; every non-missing failure then propagates.
            val raw = withLocalIoRetryBlocking { Files.readString(file) }
            val decoded = protector?.let { decodeWithAnyId(it, raw) }
            if (migrate && decoded != null && !decoded.protected) {
                // Protect even malformed legacy content before parsing it. This removes any
                // plaintext tokens from disk without making migration depend on valid JSON.
                saveSerialized { raw }
            }
            val parsed = json.decodeFromString(serializer, decoded?.plaintext ?: raw)
            if (!validate(parsed)) {
                log.warn(
                    "Credential file at {} failed the validate() guard — discarding so caller refreshes",
                    file,
                )
                return null
            }
            parsed
        } catch (e: CredentialProtectionException) {
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            // A decode error's message quotes the JSON it choked on, which for a credential file is the
            // secret itself; name the shape instead (issue#83 E).
            log.warn(
                "Credential file at {} is not readable ({}); discarding so the caller refreshes",
                file,
                describeDecodeError(e),
            )
            null
        } catch (e: NoSuchFileException) {
            log.warn("Credential file at {} vanished while it was read; treating as signed out", file, e)
            null
        } catch (e: Exception) {
            log.warn("Failed to load credentials from {}: {}", file, e.message)
            throw e
        }
    }

    /**
     * Persist [value] atomically.
     *
     * UD-312 invariant: write to a sibling `.tmp` file (owner-only,
     * synced, see [OwnerOnlyAtomicWrite]) then
     * `Files.move(... ATOMIC_MOVE)` so any concurrent reader (another
     * coroutine on the same JVM, or the MCP / tray reading the same
     * file) sees either the old contents or the new contents — never
     * a half-written truncate-and-rewrite. Falls back to a non-atomic
     * `REPLACE_EXISTING` move on filesystems that reject `ATOMIC_MOVE`
     * (some Windows network shares); the race window shrinks but doesn't
     * fully close — that's a documented limitation.
     *
     * Side-effects: the directory and the file are made reachable by the
     * current user only ([OwnerOnly]: `rwx------` / `rw-------` on POSIX,
     * an owner-only ACL on Windows). Throws [java.io.IOException] when
     * that fails; the directory is checked before the file is written.
     */
    public fun save(value: T) {
        saveSerialized { json.encodeToString(serializer, value) }
    }

    private fun saveSerialized(serialize: () -> String) {
        Files.createDirectories(dir)
        OwnerOnly.requireDirectory(dir)
        val previousReference = if (Files.exists(file)) Files.readString(file) else null
        val id = canonicalId()
        val backend = protector
        var onDisk: String? = null
        var committed = false
        try {
            // The shared write (OwnerOnlyAtomicWrite) asks for the bytes only once its owner-only temp file exists, then syncs
            // them and moves the file over [file]: a reader sees the old contents or the new ones, never a torn file, and a
            // power loss never leaves a renamed-but-empty credential file.
            OwnerOnlyAtomicWrite.replace(file, {
                val serialized = serialize()
                val written = protector?.protect(id, serialized) ?: serialized
                onDisk = written
                written.toByteArray(Charsets.UTF_8)
            })
            committed = true
            OwnerOnly.requireFile(file)
            if (backend != null && previousReference != null) {
                // The prior version may be bound to either spelling (see candidateIds): discard under each.
                val discarded = candidateIds().map { candidate -> runCatching { backend.discard(candidate, previousReference) } }
                if (discarded.none { it.isSuccess }) log.warn("Could not remove the prior protected credential version for {}", file)
            }
        } finally {
            if (!committed && backend != null) {
                val reference = onDisk
                if (reference != null) runCatching { backend.discard(id, reference) }
            }
        }
    }

    /** Delete the credential file if it exists. No-op otherwise. */
    public fun delete() {
        protector?.let { p -> candidateIds().forEach { p.delete(it) } }
        Files.deleteIfExists(file)
    }

    private fun literalId(): String = file.toAbsolutePath().normalize().toString()

    /**
     * The identity the protector binds the stored data to: the file's real path, so that a folder spelled in another case,
     * by its 8.3 short name or through a junction or symlink is one identity (`normalize()` alone keeps the spelling).
     * A file that does not exist yet takes its folder's real path, a folder that does not exist the literal path.
     */
    private fun canonicalId(): String =
        runCatching { file.toRealPath().toString() }
            .recoverCatching { dir.toRealPath().resolve(fileName).toString() }
            .getOrElse { literalId() }

    /** The canonical identity first, then the literal one that every file written before the canonical id is bound to. */
    private fun candidateIds(): List<String> = listOf(canonicalId(), literalId()).distinct()

    private fun decodeWithAnyId(
        backend: CredentialProtector,
        raw: String,
    ): CredentialData {
        var first: CredentialProtectionException? = null
        for (id in candidateIds()) {
            try {
                return backend.decode(id, raw)
            } catch (e: CredentialProtectionException) {
                first = first ?: e
            }
        }
        throw first ?: CredentialProtectionException("no credential identity for $file")
    }
}
