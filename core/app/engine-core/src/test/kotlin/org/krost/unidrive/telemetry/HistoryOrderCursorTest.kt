package org.krost.unidrive.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ordered cursor (gkrost/unidrive-cli#848): the token round-trips, a foreign one is refused, the filter hash is stable. */
class HistoryOrderCursorTest {
    private val row = TransferRow("op-1", "p1", TransferDirection.UPLOAD, "/f.txt", queuedAt = 1_000, finishedAt = 2_000, bytesTotal = 30, result = TransferResult.FAILED)

    @Test
    fun `a cursor round-trips for every order`() {
        for (field in HistoryOrderField.entries) {
            for (direction in HistoryOrderDirection.entries) {
                val order = HistoryOrder(field, direction)
                if (order.isDefault) continue
                val cursor = HistoryOrderCursor.of(row, order, "0123456789abcdef")
                assertEquals(cursor, checkNotNull(HistoryOrderCursor.decode(cursor.encode())), "$field $direction round-trips")
            }
        }
    }

    @Test
    fun `the cursor carries the row's key for its field`() {
        assertEquals(false, HistoryOrderCursor.of(row, HistoryOrder(HistoryOrderField.SIZE, HistoryOrderDirection.ASC), "h").keyNull)
        assertEquals(30L, HistoryOrderCursor.of(row, HistoryOrder(HistoryOrderField.SIZE, HistoryOrderDirection.ASC), "h").keyNumber)
        assertEquals("/f.txt", HistoryOrderCursor.of(row, HistoryOrder(HistoryOrderField.PATH, HistoryOrderDirection.ASC), "h").keyText)
        assertEquals(2_000L, HistoryOrderCursor.of(row, HistoryOrder(HistoryOrderField.TIME, HistoryOrderDirection.ASC), "h").keyNumber)
        assertEquals(
            HistoryOrder.rankOf(TransferResult.FAILED).toLong(),
            HistoryOrderCursor.of(row, HistoryOrder(HistoryOrderField.RESULT, HistoryOrderDirection.ASC), "h").keyNumber,
        )
        // A size that was not measured carries the null flag, in both directions.
        val noSize = row.copy(bytesTotal = null)
        for (direction in HistoryOrderDirection.entries) {
            val cursor = HistoryOrderCursor.of(noSize, HistoryOrder(HistoryOrderField.SIZE, direction), "h")
            assertTrue(cursor.keyNull, "unmeasured after every known value, $direction too")
        }
    }

    @Test
    fun `a token that is not one this build issued is refused`() {
        val good = HistoryOrderCursor.of(row, HistoryOrder(HistoryOrderField.SIZE, HistoryOrderDirection.ASC), "0123456789abcdef").encode()
        val decoded = checkNotNull(HistoryOrderCursor.decode(good))
        for (bad in listOf("", "not-a-cursor", "!!!")) assertNull(HistoryOrderCursor.decode(bad), "'$bad' is not a token")
        // A v1 time cursor is not an ordered one.
        assertNull(HistoryOrderCursor.decode(HistoryCursor(1_000, "op-1").encode()), "the default order's cursor pages no other order")
        // A shape this build does not issue: wrong version, unknown field or direction, a hash that is not 16 hex,
        // an empty operation id, a number where a path goes, a path where a number goes.
        val flip =
            { token: String, from: String, to: String ->
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token.replaceFirst(from, to).toByteArray())
            }
        val raw = java.util.Base64.getUrlDecoder().decode(good).toString(Charsets.UTF_8)
        assertNull(HistoryOrderCursor.decode(flip(raw, "2.", "9.")), "wrong version")
        assertNull(HistoryOrderCursor.decode(flip(raw, ".size.", ".bytes.")), "unknown field")
        assertNull(HistoryOrderCursor.decode(flip(raw, ".asc", ".up")), "unknown direction")
        assertNull(HistoryOrderCursor.decode(flip(raw, ".0123456789abcdef.", ".0123456789abcdeZ.")), "hash is not hex")
        assertNull(HistoryOrderCursor.decode(flip(raw, ".k.", ".x.")), "unknown null flag")
        assertNull(HistoryOrderCursor.decode(flip(raw, ".30.", ".")), "shape does not hold")
        assertNull(HistoryOrderCursor.decode(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2.size.asc.0123456789abcdef.k.-.-".toByteArray())), "no operation id")
        // The decoded one re-encodes to itself.
        assertEquals(good, decoded.encode())
        assertNotEquals(good, decoded.copy(operationId = "op-2").encode())
    }

    @Test
    fun `the filter hash is stable and distinguishes filter sets`() {
        val empty = HistoryOrderCursor.filterHash(emptySet(), emptySet(), null, null, null)
        assertEquals(empty, HistoryOrderCursor.filterHash(emptySet(), emptySet(), null, null, null), "stable across calls and restarts (no randomness)")
        assertTrue(empty.matches(Regex("[0-9a-f]{16}")))
        assertNotEquals(empty, HistoryOrderCursor.filterHash(setOf(TransferDirection.UPLOAD), emptySet(), null, null, null), "a direction filter changes it")
        assertNotEquals(empty, HistoryOrderCursor.filterHash(emptySet(), setOf(TransferResult.FAILED), null, null, null), "a result filter changes it")
        assertNotEquals(empty, HistoryOrderCursor.filterHash(emptySet(), emptySet(), "/docs/", null, null), "a prefix changes it")
        assertNotEquals(empty, HistoryOrderCursor.filterHash(emptySet(), emptySet(), null, 1_000L, null), "a from changes it")
        assertNotEquals(empty, HistoryOrderCursor.filterHash(emptySet(), emptySet(), null, null, 2_000L), "a to changes it")
        // Two spellings of the same filter set normalise to one hash (a multi-value result filter's order among them).
        assertEquals(
            HistoryOrderCursor.filterHash(emptySet(), setOf(TransferResult.FAILED, TransferResult.SUCCESS), null, null, null),
            HistoryOrderCursor.filterHash(emptySet(), setOf(TransferResult.SUCCESS, TransferResult.FAILED), null, null, null),
            "the same filter set in another order is the same filter set",
        )
    }

    @Test
    fun `the result rank is the contract's`() {
        assertEquals(0, HistoryOrder.rankOf(TransferResult.FAILED))
        assertEquals(1, HistoryOrder.rankOf(TransferResult.CONFLICT))
        assertEquals(2, HistoryOrder.rankOf(TransferResult.INTERRUPTED))
        assertEquals(3, HistoryOrder.rankOf(TransferResult.RUNNING))
        assertEquals(4, HistoryOrder.rankOf(TransferResult.SKIPPED))
        assertEquals(5, HistoryOrder.rankOf(TransferResult.SUCCESS))
        assertEquals(
            listOf(TransferResult.FAILED, TransferResult.CONFLICT, TransferResult.INTERRUPTED, TransferResult.RUNNING, TransferResult.SKIPPED, TransferResult.SUCCESS),
            HistoryOrder.RESULT_RANK.entries.sortedBy { it.value }.map { it.key },
            "the rank's order is the contract's, part of v1",
        )
    }
}
