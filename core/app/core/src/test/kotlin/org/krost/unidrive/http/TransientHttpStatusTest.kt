package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * archive#1256 B: one rule for "this status from a token endpoint says nothing about the credential", next to
 * [HttpRetryBudget.isRetriableIoException] that the refresh classifiers already share.
 */
class TransientHttpStatusTest {
    @Test
    fun `408, 429 and every 5xx are transient`() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 520, 524, 599)) {
            assertTrue(HttpRetryBudget.isTransientHttpStatus(status), "HTTP $status")
        }
    }

    @Test
    fun `a rejected credential, a bad request and a success are not transient`() {
        for (status in listOf(200, 204, 301, 400, 401, 403, 404, 409, 410, 499, 600)) {
            assertFalse(HttpRetryBudget.isTransientHttpStatus(status), "HTTP $status")
        }
    }
}
