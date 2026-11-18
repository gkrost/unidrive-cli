package org.krost.unidrive.cli

import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.xtra.XtraAgeKeyStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.krost.unidrive.testing.TestScratch

class XtraProviderWiringTest {

    // An age-v1 profile owns its key material end-to-end: the identity store is the only key file it needs, so
    // the wiring must not ask for the RSA pair — no xtra.key exists anywhere in the config dir (the wiring that
    // demanded one exited here), and none may be constructed or loaded behind the provider it hands back.
    @Test
    fun `an age-v1 profile wires without an RSA key file`() {
        val dir = TestScratch.directory("xtra-wiring-test")
        val tokens = Files.createDirectories(dir.resolve("tokens"))
        XtraAgeKeyStore(tokens.resolve("xtra-age.key")).generate("testpass1234".toCharArray())

        val wired = Main().wrapWithXtra(
            RawProvider(type = "localfs", mode = "mount", xtra_encryption = true, xtra_algorithm = "age-v1"),
            FakeCloudProvider(),
            tokens.resolve("token.json"),
        )

        assertNotNull(wired)
    }
}
