package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The recovery-upload scope a [HydrationImpl] host gets when it passes none. A [SupervisorJob], so one failed recovery
 * upload cannot cancel the uploads queued behind it (a plain [kotlinx.coroutines.Job] fails the whole scope on the first
 * failure, and every launch into it afterwards is dead). The daemon's own wiring passes a supervising scope as well.
 */
internal fun defaultRecoveryUploadScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
