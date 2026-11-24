package org.krost.unidrive.sync

/**
 * gkrost/unidrive-cli#926 S4: who gets a connection slot of [IpcServer], and which connections its idle timeout spares.
 *
 * - A connection that has not finished its handshake does not take one of the `maxClients` slots; such connections have a
 *   small cap of their own ([MAX_PENDING_HANDSHAKES]). When that cap is full, a new connection closes the one that has waited
 *   longest, so connections that never send a line cannot keep a real client out. In all, at most `maxClients` +
 *   [MAX_PENDING_HANDSHAKES] connections are open. Without a handshake (no [IpcAuth]) every connection takes a slot, as before.
 * - A read-scope connection is spared by the idle timeout only while it holds something (a subscription, an open handle: the
 *   holds the server was given); having run an exempt verb once is not enough. A full-scope connection is spared as before.
 */
internal object IpcClientSlots {
    const val MAX_PENDING_HANDSHAKES = 4

    sealed interface Admission {
        data object Admit : Admission

        /** All slots are taken: refused with `too_many_clients`. */
        data object Refuse : Admission

        /** Admitted after [oldest], the connection that has waited longest for its handshake, is closed. */
        data class EvictThenAdmit(val oldest: String) : Admission
    }

    /** [connections] in the order they were accepted; [pending] tells which have not finished their handshake. */
    fun admission(
        connections: List<String>,
        pending: (String) -> Boolean,
        maxClients: Int,
        maxPending: Int = MAX_PENDING_HANDSHAKES,
    ): Admission {
        val waiting = connections.filter(pending)
        return when {
            connections.size - waiting.size >= maxClients -> Admission.Refuse
            waiting.size >= maxPending -> Admission.EvictThenAdmit(waiting.first())
            else -> Admission.Admit
        }
    }

    /** True when the idle timeout spares a connection: see the class comment. */
    fun idleExempt(
        usedExemptVerb: Boolean,
        readScope: Boolean,
        holds: Boolean,
    ): Boolean = holds || (usedExemptVerb && !readScope)
}
