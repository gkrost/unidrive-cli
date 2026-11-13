package org.krost.unidrive.internxt

import java.security.MessageDigest

/**
 * The decrypted names of legacy rows (archive#314), kept so a listing pays the PBKDF2-SHA512 derivation once per row
 * and not once per row per pass. A name that does not decrypt is kept as a miss too: it fails the same way every
 * pass. Keyed by the ciphertext and its parent scope; the mnemonic enters only as a truncated SHA-256 digest, so the
 * cache holds no copy of it and two mnemonics do not share entries. Bounded: the least recently used entry goes first.
 */
internal class LegacyNameCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val onFailure: (Throwable) -> Unit = {},
    private val decrypt: (encryptedName: String, password: String) -> String,
) {
    private data class Key(
        val encryptedName: String,
        val parentUuid: String,
        val scope: String,
    )

    // A miss is kept as null; containsKey tells it apart from an entry never tried.
    private val entries =
        object : LinkedHashMap<Key, String?>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String?>): Boolean = size > maxEntries
        }

    /** The plain name, or null when [encryptedName] does not decrypt under [mnemonic] and [parentUuid]. */
    fun resolve(
        encryptedName: String,
        mnemonic: String,
        parentUuid: String,
    ): String? {
        val key = Key(encryptedName, parentUuid, mnemonicScope(mnemonic))
        synchronized(entries) {
            if (entries.containsKey(key)) return entries[key]
        }
        // Outside the lock: two threads may both derive the same name once; neither blocks the other's listing.
        val plain = runCatching { decrypt(encryptedName, "$mnemonic-$parentUuid") }.onFailure(onFailure).getOrNull()
        synchronized(entries) { entries[key] = plain }
        return plain
    }

    private fun mnemonicScope(mnemonic: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(mnemonic.toByteArray(Charsets.UTF_8))
            .copyOf(DIGEST_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIGEST_BYTES = 16
        const val DEFAULT_MAX_ENTRIES = 10_000
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}
