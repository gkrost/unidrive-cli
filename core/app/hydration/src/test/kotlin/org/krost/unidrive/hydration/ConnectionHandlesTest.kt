package org.krost.unidrive.hydration

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionHandlesTest {
    private val handles = ConnectionHandles(limit = 1)

    private suspend fun open(id: String, read: Boolean = true): String =
        handles.admit("owner", id, { it.message }) {
            handles.register("owner", id, "/file", read)
            "ok"
        }

    @Test
    fun open_read_past_the_handle_cap_is_refused() = runBlocking {
        handles.admit("owner", "pending", { error(it.message) }) {
            assertEquals("too_many_handles", open("another"))
        }
        assertEquals("ok", open("one"))
        assertEquals("ok", open("one"))
        assertEquals("too_many_handles", open("two"))
    }

    @Test
    fun read_scope_can_close_its_own_read_handle() = runBlocking {
        open("one")
        assertEquals("/file", handles.close("owner", "one", ownReadOnly = true))
        assertEquals("ok", open("two", read = false))
        assertNull(handles.close("owner", "two", ownReadOnly = true))
    }

    @Test
    fun read_scope_cannot_close_another_connections_handle() = runBlocking {
        open("one")
        assertNull(handles.close("other", "one", ownReadOnly = true))
        assertEquals("/file", handles.close("other", "one", ownReadOnly = false))
    }
}
