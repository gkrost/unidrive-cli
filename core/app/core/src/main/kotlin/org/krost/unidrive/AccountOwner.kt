package org.krost.unidrive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The stable id of the user whose drive a profile's sync state describes, kept in the profile folder as [FILE_NAME]
 * (docs/dev/specs/cli-account-name.md, "Account owner").
 *
 * A sign-in compares the user it signed in with this id BEFORE it stores the credentials: another user is refused
 * ([AccountChangedException], token `account_changed`), so a profile's state.db never describes one user's drive while
 * its credentials reach another's. The id belongs to the state, not to the credentials: a sign-out keeps it (the kept
 * state is still that user's), and only deleting the state (`logout --delete-state`, `profile remove`) removes it.
 *
 * The id comes from the provider's identity answer at sign-in: the drive owner id (`GET /me/drive` `owner.user.id`) for OneDrive,
 * the user uuid of the login answer for Internxt, the `account_id` of the token answer for Dropbox. Providers without a user
 * (S3, SFTP, WebDAV, local folders) keep none and are never checked. Personal data: never logged, never in a reply.
 */
object AccountOwner {
    private val log = org.slf4j.LoggerFactory.getLogger(AccountOwner::class.java)

    const val FILE_NAME = "account_id"

    /** What a sign-in may do with the user it signed in, decided before anything is stored. */
    enum class Verdict {
        /** The stored id is that user's: store the credentials. */
        SAME,

        /** No id is stored (a new profile, or one signed in before ids were kept): store, then [record] the user. */
        ADOPT,

        /** An id is stored and the provider gave none, or none that could be read: nothing can be compared. */
        UNVERIFIED,

        /** An id is stored and the user is another one. */
        CHANGED,
    }

    /** The stored id, or null when none was stored or the file cannot be read. */
    fun read(profileDir: Path): String? =
        try {
            Files.readString(profileDir.resolve(FILE_NAME), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }

    /** The verdict for a sign-in as [signedIn] (null: the provider could not say who signed in). */
    fun verdict(
        profileDir: Path,
        signedIn: String?,
    ): Verdict {
        val stored = read(profileDir) ?: return Verdict.ADOPT
        val user = signedIn?.trim()?.takeIf { it.isNotEmpty() } ?: return Verdict.UNVERIFIED
        // Graph ids are GUID-like and compared without case; ids of two users differ in more than case.
        return if (stored.equals(user, ignoreCase = true)) Verdict.SAME else Verdict.CHANGED
    }

    /**
     * Throws [AccountChangedException] or [AccountUnverifiedException] unless a sign-in as [signedIn] may store its
     * credentials. Call before the credentials are written; [record] after.
     */
    fun requireSameUser(
        profileDir: Path,
        signedIn: String?,
    ) {
        when (verdict(profileDir, signedIn)) {
            Verdict.SAME, Verdict.ADOPT -> Unit
            Verdict.CHANGED -> throw AccountChangedException()
            Verdict.UNVERIFIED -> throw AccountUnverifiedException()
        }
    }

    /**
     * Keeps [signedIn] as the profile's user when none is stored yet; a stored id is never replaced here (only deleting
     * the state removes it). A blank [signedIn] or a write that fails leaves the profile without an id: the next sign-in
     * then adopts its user, as for a profile signed in before ids were kept. Never fails the sign-in.
     */
    fun record(
        profileDir: Path,
        signedIn: String?,
    ) {
        val id = signedIn?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (read(profileDir) != null) return
        write(profileDir, id)
    }

    internal fun write(
        profileDir: Path,
        id: String,
    ) {
        try {
            Files.createDirectories(profileDir)
            val tmp = Files.createTempFile(profileDir, "$FILE_NAME.", ".tmp")
            Files.writeString(tmp, id, Charsets.UTF_8)
            Files.move(tmp, profileDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // The next sign-in adopts again; the credentials are stored either way.
        }
    }

    /**
     * The provider-based form of [recordIfMissing] for a provider that is signed in: one [CloudProvider.currentAccountId]
     * call with the credentials it runs with. A provider that is not signed in (its credentials were rejected, or none
     * were stored) is not asked.
     */
    suspend fun recordIfMissing(
        profileDir: Path,
        provider: CloudProvider,
        timeoutMs: Long = RECORD_TIMEOUT_MS,
    ): Boolean {
        if (!provider.isAuthenticated) return false
        return recordIfMissing(profileDir, timeoutMs) { provider.currentAccountId() }
    }

    /** The identity call of [recordIfMissing] is given this long. */
    const val RECORD_TIMEOUT_MS = 10_000L

    /**
     * For a profile signed in before ids were kept: asks [fetch] once for the id of the user of the CURRENT credentials
     * and records it. Never at the cost of the start: an id already stored means no call at all, a failed or slow
     * (more than [timeoutMs]) call logs one line and records nothing, so the next start tries again. A stored id is never
     * replaced. Returns whether an id was recorded. Logs "account id recorded" with a short hash, never the id.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun recordIfMissing(
        profileDir: Path,
        timeoutMs: Long = RECORD_TIMEOUT_MS,
        fetch: suspend () -> String?,
    ): Boolean {
        if (read(profileDir) != null) return false
        val id =
            try {
                withTimeoutOrNull(timeoutMs) { fetch() }
                    ?: run {
                        log.info("account id not recorded: the provider did not answer within {} ms or gave none; tried again at the next start", timeoutMs)
                        return false
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class only: a message may quote the provider's answer.
                log.info("account id not recorded: the identity call failed ({}); tried again at the next start", e.javaClass.simpleName)
                return false
            }
        record(profileDir, id)
        val stored = read(profileDir) ?: return false
        if (!stored.equals(id.trim(), ignoreCase = true)) return false
        log.info("account id recorded (ref {})", shortHash(stored))
        return true
    }

    private fun shortHash(id: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)), 0, 4)
}

/**
 * A sign-in as another user than the one the profile's sync state belongs to ([AccountOwner]); nothing was stored and
 * the earlier credentials are as they were. The message names no user or id.
 */
class AccountChangedException :
    AuthenticationException(
        "this is another account than the one this profile belongs to; nothing was stored. Add it as a new account, " +
            "or delete this profile's sync state first (logout --delete-state) to start the profile over with it",
    )

/** A sign-in whose user the provider did not say while the profile has a stored user; nothing was stored. */
class AccountUnverifiedException :
    AuthenticationException("the provider did not say which account signed in, so it could not be compared; nothing was stored. Try again")
