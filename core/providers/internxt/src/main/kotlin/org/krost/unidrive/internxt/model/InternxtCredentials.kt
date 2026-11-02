package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable
import org.krost.unidrive.auth.maskSecret

@Serializable
data class InternxtCredentials(
    val jwt: String,
    val mnemonic: String,
    val rootFolderId: String,
    val email: String,
    val bridgeUser: String = "",
    val bridgeUserId: String = "",
    val bucket: String = "",
) {
    override fun toString(): String =
        "InternxtCredentials(jwt=${maskSecret(jwt)}, mnemonic=${maskSecret(mnemonic)}, rootFolderId=$rootFolderId, email=$email, " +
            "bridgeUser=$bridgeUser, bridgeUserId=$bridgeUserId, bucket=$bucket)"
}
