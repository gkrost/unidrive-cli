package org.krost.unidrive.internxt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.krost.unidrive.auth.maskSecret

@Serializable
data class LoginChallengeResponse(
    val sKey: String? = null,
    val tfa: Boolean = false,
)

@Serializable
data class LoginAccessResponse(
    val newToken: String,
    val token: String? = null,
    val user: InternxtUser,
) {
    override fun toString(): String = "LoginAccessResponse(newToken=${maskSecret(newToken)}, token=${maskSecret(token)}, user=$user)"
}

@Serializable
data class InternxtUser(
    val mnemonic: String,
    @SerialName("root_folder_id") val rootFolderIdNum: Long? = null,
    val rootFolderId: String? = null,
    val uuid: String? = null,
    val email: String? = null,
    val bucket: String? = null,
    val bridgeUser: String? = null,
    val userId: String? = null,
) {
    override fun toString(): String =
        "InternxtUser(mnemonic=${maskSecret(mnemonic)}, rootFolderIdNum=$rootFolderIdNum, rootFolderId=$rootFolderId, uuid=$uuid, " +
            "email=$email, bucket=$bucket, bridgeUser=$bridgeUser, userId=$userId)"
}

@Serializable
data class TokenRefreshResponse(
    val newToken: String,
) {
    override fun toString(): String = "TokenRefreshResponse(newToken=${maskSecret(newToken)})"
}
