package org.krost.unidrive.dropbox

import kotlin.test.Test
import kotlin.test.assertEquals

/** archive#1054 S5: the CLI lists this file name among the credential files a sign-out removes; the name must not drift from it. */
class DropboxPendingFlowFileNameTest {
    @Test
    fun `the pending sign-in file is the one the CLI lists as a credential file`() {
        assertEquals("pending_dropbox_flow.json", DropboxProviderFactory.PENDING_FLOW_FILE)
    }
}
