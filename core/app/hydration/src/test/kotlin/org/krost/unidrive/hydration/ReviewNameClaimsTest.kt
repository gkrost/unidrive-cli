package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #517 review: two requests using the same spelling share one record, and the record's lifetime is not any
 * single claim's — a rollback lifts it only when it was the last reference and no verb has committed it, so a
 * shared reservation (and a committed one) survives another claimant's failed verb. Names are built from code
 * points so the source stays ASCII.
 */
class ReviewNameClaimsTest {
    private val nfc = "caf" + String(intArrayOf(0xE9), 0, 1) + ".txt"
    private val nfd = "cafe" + String(intArrayOf(0x301), 0, 1) + ".txt"

    @Test
    fun `a rollback of one claimant does not lift a shared spelling's reservation`() {
        val spellings = NameSpellings()
        val first = assertNotNull(spellings.claim("/$nfc"))
        val second = assertNotNull(spellings.claim("/$nfc"))

        spellings.rollback(first)
        assertTrue(spellings.clashes("/$nfd"), "the second claimant's reservation survives the first's rollback")

        spellings.commit(second)
        assertTrue(spellings.clashes("/$nfd"), "and it survives the second claimant's commit too")
    }

    @Test
    fun `a committed claim outlives another claimant's rollback`() {
        val spellings = NameSpellings()
        val first = assertNotNull(spellings.claim("/$nfc"))
        spellings.commit(first)
        val second = assertNotNull(spellings.claim("/$nfc"))

        spellings.rollback(second)
        assertTrue(spellings.clashes("/$nfd"), "the record is owned by the success, not by the claim")
    }

    @Test
    fun `the last claimant's rollback frees the name when nothing has committed`() {
        val spellings = NameSpellings()
        val first = assertNotNull(spellings.claim("/$nfc"))
        val second = assertNotNull(spellings.claim("/$nfc"))

        spellings.rollback(first)
        spellings.rollback(second)
        assertFalse(spellings.clashes("/$nfd"), "no claimant and no success: the spelling is free again")
    }

    @Test
    fun `a failed claimant's rollback leaves the committed record of a past success`() {
        val spellings = NameSpellings(store = RecordingStore())
        val first = assertNotNull(spellings.claim("/$nfc"))
        spellings.commit(first)
        val second = assertNotNull(spellings.claim("/$nfc"))

        spellings.rollback(second)
        assertTrue(spellings.clashes("/$nfd"), "the store-backed record is not lifted by a rollback either")

        // And a restart loads the committed record back the same way.
        val reloaded = NameSpellings(store = RecordingStore(mapOf("/$nfc" to "/$nfc")))
        assertTrue(reloaded.clashes("/$nfd"), "the loaded record is owned by its past success")
        val claim = assertNotNull(reloaded.claim("/$nfc"))
        reloaded.rollback(claim)
        assertTrue(reloaded.clashes("/$nfd"), "a claim on a loaded record cannot lift it by rolling back")
    }

    @Test
    fun `the claim answer still names the spelling it holds`() {
        val spellings = NameSpellings()
        val claim = assertNotNull(spellings.claim("/$nfc"))
        assertEquals(listOf("/$nfc" to "/$nfc"), claim)
    }

    private class RecordingStore(private val seed: Map<String, String> = emptyMap()) : NameSpellingStore {
        val recorded = mutableListOf<Pair<String, String>>()

        override fun load(): Map<String, String> = seed

        override fun record(
            nfcPath: String,
            rawPath: String,
        ) {
            recorded += nfcPath to rawPath
        }

        override fun forgetUnder(nfcPath: String) {
            recorded.removeAll { it.first == nfcPath || it.first.startsWith("$nfcPath/") }
        }
    }
}
