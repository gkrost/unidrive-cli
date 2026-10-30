package org.krost.unidrive

import org.slf4j.MDC

/**
 * gkrost/unidrive-cli#225: the log context (the slf4j MDC: the `profile` and `build` the log pattern prints) of the code that
 * hands work to a thread it does not run on: a writer thread of its own, a library's event thread, an executor. A coroutine
 * carries its MDC in an `MDCContext` element; such a thread has none, and its lines printed `[*]` in the log two daemons
 * share. Captured where the thread or the callback is set up, applied where the work runs.
 */
class MdcSnapshot private constructor(
    private val map: Map<String, String>,
) {
    /** For a thread that runs nothing else (a writer loop, a selector): its lines carry this context from here on. */
    fun applyToThisThread() {
        if (map.isNotEmpty()) MDC.setContextMap(map)
    }

    /** For a thread shared with others (a library's event thread): [block] runs in this context, and the thread gets its own back. */
    fun <T> around(block: () -> T): T {
        if (map.isEmpty()) return block()
        val own = MDC.getCopyOfContextMap()
        MDC.setContextMap(map)
        try {
            return block()
        } finally {
            if (own == null) MDC.clear() else MDC.setContextMap(own)
        }
    }

    /** [task], run [around] this context: for an executor or a scheduler. */
    fun wrap(task: Runnable): Runnable = Runnable { around { task.run() } }

    companion object {
        /** The calling thread's MDC as it is now; an empty one changes nothing where it is applied. */
        fun capture(): MdcSnapshot = MdcSnapshot(MDC.getCopyOfContextMap().orEmpty())
    }
}
