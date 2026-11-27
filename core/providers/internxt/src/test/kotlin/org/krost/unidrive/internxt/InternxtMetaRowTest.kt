package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals

class InternxtMetaRowTest {
    @Test
    fun `the status decides before a flag is read`() {
        assertEquals(MetaRowState.GONE, metaRowState("DELETED", deleted = true, removed = false))
        assertEquals(MetaRowState.TRASHED, metaRowState("TRASHED", deleted = false, removed = true))
    }

    @Test
    fun `a row with no decisive status is gone for good before it is trashed`() {
        assertEquals(MetaRowState.GONE, metaRowState("EXISTS", deleted = true, removed = true))
    }

    @Test
    fun `the wire shapes of the server`() {
        assertEquals(MetaRowState.TRASHED, metaRowState("TRASHED", deleted = true, removed = false))
        assertEquals(MetaRowState.GONE, metaRowState("DELETED", deleted = false, removed = true))
        assertEquals(MetaRowState.LIVE, metaRowState("EXISTS", deleted = false, removed = false))
    }
}
