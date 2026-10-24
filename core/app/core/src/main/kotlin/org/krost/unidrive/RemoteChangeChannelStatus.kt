package org.krost.unidrive

/**
 * A reading of a provider's remote-change push channel ([CloudProvider.remoteChangeChannelStatus]), reported in
 * `daemon.status` `notifications`. Counters run since the provider was created.
 *
 * - [lastHintAtMs]: epoch ms of the last hint the channel gave the engine, null before the first.
 * - [hints]: how many hints the channel gave (a notification, a rotation, a (re)connect).
 * - [expiresAtMs]: epoch ms at which the current endpoint expires, null when none is known or the channel is off.
 * - [rotations]: how many times the channel replaced its endpoint with a new one.
 */
data class RemoteChangeChannelStatus(
    val state: State,
    val lastHintAtMs: Long? = null,
    val hints: Long = 0,
    val expiresAtMs: Long? = null,
    val rotations: Long = 0,
) {
    enum class State(
        val wireName: String,
    ) {
        /** The socket is connected to a valid endpoint. */
        CONNECTED("connected"),

        /** Fetching an endpoint, connecting, or waiting in the back-off before the next attempt. */
        CONNECTING("connecting"),

        /** Not open: switched off by the profile, not asked for yet, closed or signed out. */
        OFF("off"),
    }
}
