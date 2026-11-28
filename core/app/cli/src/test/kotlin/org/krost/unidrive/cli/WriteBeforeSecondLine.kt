package org.krost.unidrive.cli

import java.io.InputStream

/**
 * Standard input that runs [beforeSecondLine] once, when the first byte past the first line is read (#1038: a vault
 * write that lands while change-passphrase waits for the new passphrase). Used by VaultStdinTest and VaultLifecycleJsonTest.
 */
internal class WriteBeforeSecondLine(
    text: String,
    private val beforeSecondLine: () -> Unit,
) : InputStream() {
    private val bytes = text.toByteArray(Charsets.UTF_8)
    private val firstLineEnd = bytes.indexOf('\n'.code.toByte()) + 1
    private var pos = 0

    override fun read(): Int {
        if (pos == firstLineEnd) beforeSecondLine()
        return if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
    }
}
