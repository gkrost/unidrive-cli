package org.krost.unidrive.onedrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.krost.unidrive.auth.maskSecret

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("ext_expires_in") val extExpiresIn: Long? = null,
) {
    override fun toString(): String =
        "TokenResponse(accessToken=${maskSecret(accessToken)}, tokenType=$tokenType, expiresIn=$expiresIn, " +
            "refreshToken=${maskSecret(refreshToken)}, scope=$scope, extExpiresIn=$extExpiresIn)"
}

@Serializable
data class Token(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Long,
    val refreshToken: String? = null,
    val scope: String? = null,
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt

    fun isNearExpiry(thresholdMs: Long = 5 * 60 * 1000): Boolean = System.currentTimeMillis() + thresholdMs >= expiresAt

    fun hasPlausibleAccessTokenShape(): Boolean = accessToken.length >= 32

    override fun toString(): String =
        "Token(accessToken=${maskSecret(accessToken)}, tokenType=$tokenType, expiresAt=$expiresAt, " +
            "refreshToken=${maskSecret(refreshToken)}, scope=$scope)"
}

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Long,
    val interval: Long,
    val message: String,
)
