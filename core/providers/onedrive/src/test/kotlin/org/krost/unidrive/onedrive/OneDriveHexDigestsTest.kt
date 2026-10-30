package org.krost.unidrive.onedrive

import kotlin.test.Test
import kotlin.test.assertEquals

/** cli#223: the short hex tags derived from a SHA-256 are the leading bytes, lower case, two digits per byte. */
class OneDriveHexDigestsTest {
    @Test
    fun `the socket endpoint tag is 8 hex digits of the SHA-256 of the url (golden)`() {
        assertEquals("5cbd381d", SocketIoEndpoint.shortHash("https://example.org/notify"))
    }

    @Test
    fun `a disambiguated server name carries ~ud and 8 hex digits of the SHA-256 of its NFC form (golden)`() {
        assertEquals("name~udc308cef8.txt", OneDriveServerNames.disambiguate("name.txt"))
    }
}
