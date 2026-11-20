package org.krost.unidrive.cli

/**
 * The refusal text for the locked write of `profile add` (interactive), or null when the profile
 * was written. [writeResult] is `writeInteractiveProfile`'s result: null when config.toml does not
 * parse, "added" / "default" on success, otherwise the reason the write was refused.
 */
internal fun interactiveWriteRefusal(writeResult: String?): String? =
    when (writeResult) {
        null -> "config.toml could not be parsed."
        "added", "default" -> null
        else -> writeResult
    }
