package org.krost.unidrive

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The name of the account a profile was signed in to, kept in the profile folder next to the credentials
 * so `profile list` can show it without a network call. Personal data: callers never log the value and
 * never write it to the audit log. A profile signed in before the name was kept has none.
 */
object AccountName {
    const val FILE_NAME = "account_name"

    /** The stored name, or null when none was stored or the file cannot be read. */
    fun read(profileDir: Path): String? =
        try {
            Files.readString(profileDir.resolve(FILE_NAME), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }

    /** Stores [name]; a blank or null [name] removes the file, so an earlier account's name never outlives a new sign-in. */
    fun write(
        profileDir: Path,
        name: String?,
    ) {
        val file = profileDir.resolve(FILE_NAME)
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        try {
            if (clean == null) {
                Files.deleteIfExists(file)
            } else {
                Files.createDirectories(profileDir)
                val tmp = Files.createTempFile(profileDir, "$FILE_NAME.", ".tmp")
                Files.writeString(tmp, clean, Charsets.UTF_8)
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            // The name is a convenience for the client; a sign-in does not fail for it.
        }
    }
}
