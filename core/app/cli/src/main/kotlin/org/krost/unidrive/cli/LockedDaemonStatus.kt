package org.krost.unidrive.cli

import org.krost.unidrive.io.ndjsonString
import org.krost.unidrive.sync.ProfileMode

/**
 * The shapes and the capability of the vault verbs and the locked daemon.status (issue#796),
 * beside the health objects they extend (StatusHealthJson.kt).
 */

/**
 * The `capabilities` array of the locked daemon.status shape (issue#796): the mode's own
 * capabilities and the vault verbs — the provider-dependent set is not known before the tail runs.
 */
internal fun lockedCapabilitiesJson(profileMode: ProfileMode): String =
    (profileMode.capabilities + VAULT_UNLOCK_CAPABILITY).joinToString(",", "[", "]") { ndjsonString(it) }

/**
 * The `daemon.status` shape of a daemon that started locked (issue#796): the handshake, mode and
 * capability members the unlocked reply carries, `locked: true`, and nothing that needs the
 * provider — it is not built until vault.unlock. The unlocked tail replaces this shape (the handler
 * delegates).
 */
internal fun lockedDaemonStatusJson(
    modeJson: String,
    capabilitiesJson: String,
    presenceJson: String,
    uptimeMs: Long,
    clientsConnected: Int,
    ipcJson: String,
): String =
    JsonMembers()
        .put("ok", "true")
        .put("protocol_version", DaemonRuntime.IPC_PROTOCOL_VERSION.toString())
        .put("contract_level", DaemonRuntime.IPC_CONTRACT_LEVEL.toString())
        .put("engine_version", ndjsonString(BuildInfo.versionString()))
        .put("engine_build_time_ms", BuildInfoReport.buildTimeMs()?.toString() ?: "null")
        .put("mode", modeJson)
        .put("capabilities", capabilitiesJson)
        .put("presence", presenceJson)
        .put("uptime_ms", uptimeMs.toString())
        .put("clients_connected", clientsConnected.toString())
        .put("locked", "true")
        .put("ipc", ipcJson)
        .render()
