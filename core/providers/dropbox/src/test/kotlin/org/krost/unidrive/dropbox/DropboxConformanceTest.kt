package org.krost.unidrive.dropbox

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.testing.CloudProviderConformanceTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SPI's conformance contracts against [DropboxProvider] over [FakeDropbox], the scripted HTTP fake.
 *
 * A scripted backend holds no state of its own, so the id-addressed contracts read the evidence off the recorded
 * requests: the delete went to the id, the move went to the id, and never to the item at the handed path. The
 * conditional-write contracts stay provider-side: a stale token is a content hash Dropbox's own comparison refuses
 * against the scripted metadata, before anything is written. And the reload contract pins the decision that closed
 * the fail-open default: `reloadCredentials` reads the stored grant only, so with nothing stored the instance is
 * simply unauthenticated — the browser sign-in of `authenticate` is unreachable from a daemon reload.
 */
class DropboxConformanceTest : CloudProviderConformanceTest() {
    override val statefulBackend: Boolean = false
    override val storedCredentialsRemovable: Boolean = true

    private lateinit var fake: FakeDropbox
    private lateinit var dir: Path
    private var ids = 0

    override fun newProvider(): CloudProvider {
        fake = FakeDropbox()
        dir = Files.createTempDirectory("conformance-dropbox")
        return FakeDropbox.provider(fake, dir)
    }

    override suspend fun seedFile(
        provider: CloudProvider,
        path: String,
        content: String,
    ): CloudItem {
        ids += 1
        val id = "id:conformance-$ids"
        // The answers the write contracts meet afterwards: the upload itself, a metadata lookup (the stale-token
        // comparisons read it), a delete and a move.
        fake.json("files/upload", FakeDropbox.file(path, id))
        fake.json("files/get_metadata", FakeDropbox.file(path, id))
        fake.json("files/delete_v2", "{}")
        fake.json("files/move_v2", """{"metadata":${FakeDropbox.file(path, id)}}""")
        return provider.upload(localFile(content), path)
    }

    override fun localFile(content: String): Path =
        Files.createTempFile("conformance-local", ".txt").also { Files.writeString(it, content) }

    // A 64-hex token: Dropbox compares it against the scripted metadata's content hash and refuses on the spot.
    override fun staleWriteToken(): String = "1".repeat(64)

    override fun recordedDeleteTargets(): List<String> =
        fake.callsTo("files/delete_v2").mapNotNull { it.args?.get("path")?.jsonPrimitive?.contentOrNull }

    override fun recordedMoveSources(): List<String> =
        fake.callsTo("files/move_v2").mapNotNull { it.args?.get("from_path")?.jsonPrimitive?.contentOrNull }

    override fun clearStoredCredentials() {
        Files.delete(dir.resolve(DropboxAuth.TOKEN_FILE))
    }
}
