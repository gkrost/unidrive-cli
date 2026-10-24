package org.krost.unidrive

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * An id that start-up recorded from a source other than the one its sign-in compares (Internxt: the token's uuid claim,
 * while the sign-in compares the login answer's `user.uuid`) is UNPROVEN: [FILE_NAME] sits next to `account_id`
 * (docs/dev/specs/cli-account-name.md, "Unproven ids"). A sign-in replaces it with the id it proved.
 */
object UnprovenAccountId {
    const val FILE_NAME = "account_id_unproven"

    /** Marks the stored id unproven. */
    fun mark(profileDir: Path) {
        try {
            Files.writeString(profileDir.resolve(FILE_NAME), "1")
        } catch (_: IOException) {
            // Without the mark the id would be taken as proven: a sign-in could then be refused. Remove the id instead.
            runCatching { Files.deleteIfExists(profileDir.resolve(AccountOwner.FILE_NAME)) }
        }
    }

    /** The stored id when it is unproven, else null. */
    fun id(profileDir: Path): String? = if (Files.exists(profileDir.resolve(FILE_NAME))) AccountOwner.read(profileDir) else null

    /**
     * A sign-in proved [id] as the stored user's id (Internxt: the login answer's `user.uuid`): it replaces the unproven
     * id and the mark goes. A blank [id] changes nothing.
     */
    fun replaceWithProven(
        profileDir: Path,
        id: String?,
    ) {
        val proven = id?.trim()?.takeIf { it.isNotEmpty() } ?: return
        AccountOwner.write(profileDir, proven)
        runCatching { Files.deleteIfExists(profileDir.resolve(FILE_NAME)) }
    }
}
