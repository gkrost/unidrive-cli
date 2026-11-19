package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#760: the verbs the daemon lets the IPC server cancel when their client goes away. A locate that a timed-out
 * client abandoned must not keep its provider calls and its connection slot; a write must never stop half-way.
 */
class HydrationCancelOnPeerGoneVerbsTest {
    @Test
    fun `locate is cancelled when its client goes away`() {
        assertTrue(HydrationIpcHandler.LOCATE in HydrationIpcHandler.CANCEL_ON_PEER_GONE_VERBS)
    }

    @Test
    fun `no write verb is cancelled when its client goes away`() {
        assertEquals(listOf(HydrationIpcHandler.LOCATE), HydrationIpcHandler.CANCEL_ON_PEER_GONE_VERBS)
        assertTrue(HydrationIpcHandler.CANCEL_ON_PEER_GONE_VERBS.all { it in HydrationIpcHandler.VERBS }, "every verb is one the handler answers")
    }
}
