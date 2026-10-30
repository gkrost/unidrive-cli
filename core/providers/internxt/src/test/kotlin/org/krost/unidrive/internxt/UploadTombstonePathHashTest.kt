package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals

/** cli#223: the per-upload key is the lower-case SHA-256 hex of the path string, 64 digits. */
class UploadTombstonePathHashTest {
    @Test
    fun `pathHash known answers`() {
        assertEquals("35a70e2c9b7d9f56c30880d982fbc7e3d5fddea11a37035a3c39d0328c265ddd", UploadTombstoneStore.pathHash("C:/x"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UploadTombstoneStore.pathHash(""))
    }
}
