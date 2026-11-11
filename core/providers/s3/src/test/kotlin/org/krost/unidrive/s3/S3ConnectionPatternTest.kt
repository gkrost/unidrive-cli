package org.krost.unidrive.s3

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the S3 provider does to its TCP connections, counted on [LoggingS3Server]. [FakeS3] has no sockets, so this is the
 * one place that needs a real server, and it goes through the production client: [S3ProviderFactory] builds the provider
 * the way the CLI does (no engine passed), so a change of the client engine (gkrost/unidrive-cli#566) is in the picture.
 * The request counts per object are in [S3RequestPatternTest].
 *
 * The first test keeps the path alive and prints the numbers. The connection tests bound the distinct connections by the
 * transfers plus [CONNECTION_SLACK]; the test with an `@Ignore` states a target for the issue it names, whoever lands that
 * issue removes the `@Ignore`.
 */
class S3ConnectionPatternTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = LoggingS3Server()
    private val tree = S3TransferTree()
    private val provider = lazy { S3ProviderFactory().create(server.profile(), tmp.newFolder("profile").toPath()) }
    private val s3 by provider

    // The server's port is released even when the provider's close throws, and a test that never needed the provider does not
    // build one here only to close it.
    @After
    fun stop() {
        try {
            if (provider.isInitialized()) s3.close()
        } finally {
            server.close()
        }
    }

    private fun upload(local: Path) = S3TransferTree.inParallel(tree.files.keys.toList()) { s3.upload(local.resolve(tree.key(it)), it) }

    private fun download(): Path =
        tmp.newFolder("downloaded").toPath().also { target ->
            S3TransferTree.inParallel(tree.files.keys.toList()) {
                val destination = target.resolve(tree.key(it))
                Files.createDirectories(destination.parent)
                s3.download(it, destination)
            }
        }

    private fun closers() =
        "closed by the client: ${server.connections().count { it.closedBy == LoggingS3Server.Closer.CLIENT }}, " +
            "by the server: ${server.connections().count { it.closedBy == LoggingS3Server.Closer.SERVER }}"

    @Test
    fun `the production client moves the tree and the numbers are printed`() {
        val uploadMark = server.mark()
        upload(tree.writeTo(tmp.newFolder("local").toPath()))
        val uploads = server.requestsSince(uploadMark)
        val uploadPeak = server.peakInFlight
        tree.files.forEach { (path, bytes) -> assertContentEquals(bytes, server.bytesOf(tree.key(path)), "the server holds the bytes of $path") }

        val downloadMark = server.mark()
        val target = download()
        val downloads = server.requestsSince(downloadMark)
        tree.files.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(target.resolve(tree.key(path))), "downloaded $path") }

        // Exploratory (gkrost/unidrive-cli#563 saw a peak of 8 requests in flight on upload with 4 transfers, cause unknown):
        // the numbers are printed, not bounded, until someone has read them.
        println("upload: ${LoggingS3Server.summary(uploads)}; peak in flight so far $uploadPeak")
        println("download: ${LoggingS3Server.summary(downloads)}; peak in flight overall ${server.peakInFlight}; ${closers()}")
        assertTrue((uploads + downloads).all { it.signed }, "every request is signed")
        assertEquals(tree.files.size, LoggingS3Server.perKey(uploads).size, "every object was uploaded")
    }

    @Test
    fun `an upload uses about as many connections as it has transfers`() {
        val mark = server.mark()
        upload(tree.writeTo(tmp.newFolder("local").toPath()))

        val requests = server.requestsSince(mark)
        tree.files.forEach { (path, bytes) -> assertContentEquals(bytes, server.bytesOf(tree.key(path)), "the server holds the bytes of $path") }
        assertConnectionsBounded("upload", requests)
    }

    @Test
    fun `a download uses about as many connections as it has transfers`() {
        tree.files.forEach { (path, bytes) -> server.seed(tree.key(path), bytes) }
        val mark = server.mark()
        val target = download()

        val requests = server.requestsSince(mark)
        tree.files.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(target.resolve(tree.key(path))), "downloaded $path") }
        assertConnectionsBounded("download", requests)
    }

    // CIO made every request its own connection (gkrost/unidrive-cli#563: 651 requests on 651 connections), ended by the client;
    // the pooling engine (gkrost/unidrive-cli#566) holds about as many as there are transfers. A bound on connections says
    // nothing about a run that sent nothing, so the run must also have sent at least a request for every object it moved.
    private fun assertConnectionsBounded(
        phase: String,
        requests: List<LoggingS3Server.Request>,
    ) {
        assertEquals(
            tree.files.keys.map(tree::key).toSet(),
            LoggingS3Server.perKey(requests).keys,
            "$phase: a request for every object of the tree; ${LoggingS3Server.summary(requests)}",
        )
        val connections = LoggingS3Server.connectionCount(requests)
        val limit = S3TransferTree.CONCURRENCY + CONNECTION_SLACK
        assertTrue(
            connections <= limit,
            "$phase: $connections distinct connections for ${requests.size} requests, at most $limit expected " +
                "(${S3TransferTree.CONCURRENCY} transfers plus $CONNECTION_SLACK); ${closers()}; peak in flight ${server.peakInFlight}; ${LoggingS3Server.summary(requests)}",
        )
    }

    // The server checks Content-MD5 as S3 does. Without the header the corrupted bytes are stored and the upload succeeds.
    @Ignore("enable with #570")
    @Test
    fun `a byte flipped on the way makes the upload fail with BadDigest`() {
        val local = tmp.newFile("flip.bin").toPath().also { Files.write(it, Random(1).nextBytes(64 * 1024)) }
        server.flipByteInNextPut()

        val failure = assertFailsWith<S3Exception> { runBlocking { s3.upload(local, "/flip.bin") } }

        assertEquals("BadDigest", failure.code, "the failure names the digest: ${failure.message}")
        assertNull(server.bytesOf("flip.bin"), "the corrupted bytes were not stored; ${LoggingS3Server.summary(server.requests())}")
    }

    private companion object {
        // Connections beyond the transfers a pooling client may hold (one retired, one in setup).
        const val CONNECTION_SLACK = 4
    }
}
