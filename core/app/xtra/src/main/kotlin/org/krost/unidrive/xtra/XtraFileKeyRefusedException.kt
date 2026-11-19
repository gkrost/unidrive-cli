package org.krost.unidrive.xtra

import java.security.GeneralSecurityException

/**
 * A stored file's wrapped-key slot that the strategy's loaded key material does not unwrap: a changed slot, or a
 * file of another installation's key (RSA-OAEP cannot tell the two apart). [XtraEncryptedProvider] refuses the
 * content permanently. A strategy raises it only around the unwrap itself, never around loading its key store, so
 * a wrong passphrase or a missing key file stays this installation's error and never quarantines content.
 */
class XtraFileKeyRefusedException(
    message: String,
    cause: Throwable,
) : GeneralSecurityException(message, cause)
