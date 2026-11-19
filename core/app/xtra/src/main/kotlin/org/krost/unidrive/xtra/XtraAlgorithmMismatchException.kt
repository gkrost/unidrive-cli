package org.krost.unidrive.xtra

import org.krost.unidrive.ProviderException

/**
 * A stored X-tra file whose [XtraHeader] names another algorithm than the profile's `xtra_algorithm`: the file was
 * written under [storedAlgorithmId] and the profile now decrypts with [configuredAlgorithmId] (an algorithm switch,
 * or a second machine on the other setting). [XtraEncryptedProvider] raises it after reading the header and before
 * any key demand, so the configured strategy never asks for a passphrase or parses a body it did not write.
 *
 * Deliberately NOT a [org.krost.unidrive.PermanentDownloadFailureException] (and so not a
 * [org.krost.unidrive.RemoteContentRefusedDownloadException]): the bytes are fine, the configuration does not name
 * them. A permanent failure is quarantined by the mirror in `state.db` (`download_quarantined`), and that flag ends
 * only with a delivery of a changed item — an unchanged file whose profile switches back to its algorithm would stay
 * quarantined for good. As a plain [ProviderException] the engines treat it as a failed transfer of this pass: the
 * mirror restores the placeholder and the recovery loop tries the row again on the next pass, the mount answers the
 * read with EIO and remembers nothing (its refusal memory holds only truncated and refused content). The daemon
 * reads `xtra_algorithm` only at start, so the outcome is re-evaluated at the restart a switch requires: once the
 * profile names the file's algorithm again, the same stored file downloads with no manual step.
 *
 * The price is paid once per object version per run: the first attempt has to download the file to read its
 * header (the provider SPI has no partial read), and the provider's [XtraAlgorithmMismatchMemory] answers every
 * later download of the same version with this same exception, transferring nothing, until the restart a switch
 * requires. Each attempt still logs a warning naming both algorithms.
 *
 * A changed algorithm byte of a file is indistinguishable from a file of the other algorithm, so it lands here and
 * not as a content refusal; the AEAD seal of the configured strategy is only consulted for a file of its own byte.
 */
class XtraAlgorithmMismatchException(
    val storedAlgorithmId: Byte,
    val configuredAlgorithmId: Byte,
    message: String,
) : ProviderException(message)

// The one refusal sentence, shared by the header check that raises it and the run's memory that replays it.
internal fun mismatchRefusal(
    storedAlgorithmId: Byte,
    configuredAlgorithmId: Byte,
    name: String,
): XtraAlgorithmMismatchException = XtraAlgorithmMismatchException(
    storedAlgorithmId,
    configuredAlgorithmId,
    "X-tra file written with algorithm $storedAlgorithmId, the profile's xtra_algorithm decrypts " +
        "algorithm $configuredAlgorithmId: $name is left alone until the profile names its algorithm again",
)
