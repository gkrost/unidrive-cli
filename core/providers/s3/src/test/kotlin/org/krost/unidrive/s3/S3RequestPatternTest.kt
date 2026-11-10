package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.krost.unidrive.PermanentDownloadFailureException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How many requests the S3 provider makes for an upload and a download of a small tree, counted on [FakeS3] (which records
 * every request, bodies included): 40 files of 1 to 64 KiB in four folders and two of about 1 MiB in a fifth, four transfers
 * at a time, the way the engine drives a provider. gkrost/unidrive-cli#563 measured these numbers on a logging stub; this
 * keeps them from drifting unseen. Connections need real sockets and are counted in [S3ConnectionPatternTest].
 *
 * Tests without an `@Ignore` pin what holds today. Tests with one state the target of an open issue and name it: whoever
 * fixes the issue removes the `@Ignore` and, where an active test above it pins the old number, deletes or flips that test.
 * A download is one GET per object since gkrost/unidrive-cli#569; an upload still ends in a HEAD (#574, see the test below);
 * since gkrost/unidrive-cli#573 a create on a vendor that refuses `If-None-Match` is backed by the listing, with no HEAD
 * before the PUT.
 * Every failure message carries the observed counts and the request sequence of the objects that broke the bound.
 */
class S3RequestPatternTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fake = FakeS3()
    private val s3 by lazy { S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", tmp.newFolder("profile").toPath()), fake.engine) }
    private val tree = S3TransferTree()

    @After
    fun stop() = s3.close()

    private fun key(path: String) = tree.key(path)

    private fun writeLocal(): Path = tree.writeTo(tmp.newFolder("local").toPath())

    private fun <T> inParallel(
        items: List<T>,
        action: suspend (T) -> Unit,
    ) = S3TransferTree.inParallel(items, action)

    private fun snapshot(): List<FakeS3.Request> = synchronized(fake.requests) { fake.requests.toList() }

    private fun label(r: FakeS3.Request): String =
        when {
            r.key == null && r.method == HttpMethod.Get -> "LIST"
            r.key == null -> "${r.method.value} bucket"
            else -> r.method.value
        }

    private fun perKey(requests: List<FakeS3.Request>): Map<String, List<String>> = requests.filter { it.key != null }.groupBy({ it.key!! }, ::label)

    /** The observed counts, and the request sequence of up to [SHOWN] of the [offending] objects, for a failure message. */
    private fun report(
        requests: List<FakeS3.Request>,
        offending: Collection<String> = emptyList(),
    ): String {
        val byKind = requests.groupingBy(::label).eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} x${it.value}" }
        val sequences = perKey(requests).values.groupingBy { it.joinToString(", ") }.eachCount().entries.sortedByDescending { it.value }.take(SHOWN)
        val shown = perKey(requests).filterKeys { it in offending }.entries.take(SHOWN).joinToString("; ") { (key, calls) -> "$key: ${calls.joinToString(", ")}" }
        return "${requests.size} requests for ${perKey(requests).size} objects: $byKind; sequences: " +
            sequences.joinToString("; ") { "[${it.key}] x${it.value}" } + if (shown.isEmpty()) "" else "; offending: $shown"
    }

    private fun createFolders() = runBlocking { tree.folders.forEach { s3.createFolder("/$it") } }

    private fun uploadAll(
        local: Path,
        paths: Collection<String> = tree.files.keys,
    ) = inParallel(paths.toList()) { s3.upload(local.resolve(key(it)), it) }

    private fun seedAll() =
        tree.files.forEach { (path, bytes) ->
            fake.objects[key(path)] = FakeS3.Obj(bytes, bytes.size.toLong(), S3Api.md5Hex(bytes), emptyMap(), Instant.parse("2026-01-01T00:00:00Z"))
        }

    private fun downloadAll(): Path {
        val target = tmp.newFolder("downloaded").toPath()
        inParallel(tree.files.keys.toList()) {
            val destination = target.resolve(key(it))
            Files.createDirectories(destination.parent)
            s3.download(it, destination)
        }
        tree.files.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(target.resolve(key(path))), "downloaded $path") }
        return target
    }

    private fun assertStored() = tree.files.forEach { (path, bytes) -> assertContentEquals(bytes, fake.objects[key(path)]?.bytes, "the fake holds the bytes of $path") }

    // ── upload ──

    @Test
    fun `an upload is a PUT and a HEAD per object when the vendor accepts If-None-Match`() {
        val local = writeLocal()
        createFolders()
        val markers = snapshot().size
        uploadAll(local)
        val requests = snapshot().drop(markers)

        assertStored()
        val report = report(requests)
        println("upload ${tree.files.size} objects: $report")
        val perKey = perKey(requests)
        assertEquals(tree.files.keys.map(::key).toSet(), perKey.keys, "every object was uploaded; $report")
        val tooMany = perKey.filterValues { it.size > 2 }.keys
        assertTrue(tooMany.isEmpty(), "upload: ${tooMany.size} of ${perKey.size} objects took more than 2 requests; ${report(requests, tooMany)}")
        // The post-upload HEAD stays until gkrost/unidrive-cli#574: it is the only source of the LastModified the engine records (see S3Provider.upload).
        assertEquals(2 * tree.files.size, requests.size, "upload: 2 requests per object (PUT, HEAD); $report")
        assertTrue(perKey.values.all { it == listOf("PUT", "HEAD") }, "upload: each object is PUT then HEAD; $report")
        assertTrue(requests.filter { it.method == HttpMethod.Put }.all { it.headers["if-none-match"] == "*" }, "upload: each create asks for a free key; $report")
    }

    @Test
    fun `an upload to a vendor that refuses If-None-Match is two requests per object after the first`() {
        val local = writeLocal()
        fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 501, "NotImplemented")
        val probe = tree.files.keys.first()
        // The refusal is learned once per provider, on the first object's refused PUT. The first goes alone so the
        // bound below is tight: an object that starts before the refusal lands spends a refused PUT of its own.
        uploadAll(local, listOf(probe))
        uploadAll(local, tree.files.keys - probe)
        val requests = snapshot()

        assertStored()
        val report = report(requests)
        println("upload ${tree.files.size} objects to a vendor without If-None-Match: $report")
        val perKey = perKey(requests)
        assertEquals(tree.files.keys.map(::key).toSet(), perKey.keys, "every object was uploaded; $report")
        // The first object costs three: PUT (501), PUT, HEAD; every later one two: the listing-backed PUT (#573), HEAD.
        assertEquals(listOf("PUT", "PUT", "HEAD"), perKey.getValue(key(probe)), "refusing vendor: the first object; ${report(requests, listOf(key(probe)))}")
        val tooMany = perKey.filterKeys { it != key(probe) }.filterValues { it != listOf("PUT", "HEAD") }.keys
        assertTrue(tooMany.isEmpty(), "refusing vendor: ${tooMany.size} later objects were not PUT then HEAD; ${report(requests, tooMany)}")
        assertEquals(2 * tree.files.size + 1, requests.size, "refusing vendor: 2 requests per object plus the one refused PUT; $report")
        assertEquals(1, requests.count { it.method == HttpMethod.Put && it.headers.containsKey("if-none-match") }, "refusing vendor: the header is sent once, then dropped; $report")
    }

    @Test
    fun `creating a folder is one marker PUT per folder`() {
        createFolders()
        val requests = snapshot()

        // Today: five folders, five requests, each a PUT of the key `<folder>/` without a body and without a guard.
        // gkrost/unidrive-cli#575 asks for no markers unless needed; the test below is its target.
        val report = report(requests)
        assertEquals(tree.folders.map { "$it/" }.toSet(), perKey(requests).keys, report)
        assertTrue(requests.all { it.method == HttpMethod.Put && it.body.isEmpty() }, "folders: one marker PUT each; $report")
        assertEquals(tree.folders.size, requests.size, "folders: ${tree.folders.size} folders, one request each; $report")
    }

    @Ignore("enable with #575")
    @Test
    fun `a folder that gets a child in the same pass gets no marker`() {
        val local = writeLocal()
        createFolders()
        uploadAll(local)
        val requests = snapshot()

        val markers = requests.filter { it.method == HttpMethod.Put && it.key!!.endsWith("/") }
        assertTrue(markers.isEmpty(), "${markers.size} marker PUTs for folders that hold files; ${report(requests, markers.map { it.key!! })}")
    }

    // ── download ──

    @Test
    fun `a download is a single GET per object`() {
        seedAll()
        downloadAll()
        val requests = snapshot()

        val report = report(requests)
        println("download ${tree.files.size} objects: $report")
        val perKey = perKey(requests)
        assertEquals(tree.files.keys.map(::key).toSet(), perKey.keys, "every object was downloaded; $report")
        val tooMany = perKey.filterValues { it != listOf("GET") }.keys
        assertTrue(tooMany.isEmpty(), "download: ${tooMany.size} of ${perKey.size} objects were not a single GET; ${report(requests, tooMany)}")
        assertEquals(tree.files.size, requests.count { it.method == HttpMethod.Get }, "download: one GET per object; $report")
        assertEquals(0, requests.count { it.method == HttpMethod.Head }, "download: no HEAD; $report")
    }

    @Test
    fun `an object that is gone is one GET and the same permanent failure`() {
        seedAll()
        val gone = tree.files.keys.first()
        fake.objects.remove(key(gone))
        val failure = assertFailsWith<PermanentDownloadFailureException> { runBlocking { s3.download(gone, tmp.newFolder("gone").toPath().resolve("x.bin")) } }
        val requests = snapshot()

        assertEquals("S3 has no object at '$gone' any more", failure.message)
        assertEquals(listOf("GET"), requests.map(::label), "gone: the 404 of the GET is the answer, no HEAD first; ${report(requests)}")
    }

    @Test
    fun `an object replaced after it was listed is downloaded as it is now, by its own Content-Length`() {
        seedAll()
        val path = tree.files.keys.first()
        val replaced = ByteArray(tree.files.getValue(path).size + 777) { (it % 251).toByte() }
        fake.objects[key(path)] = FakeS3.Obj(replaced, replaced.size.toLong(), S3Api.md5Hex(replaced), emptyMap(), Instant.parse("2026-02-02T00:00:00Z"))
        val destination = tmp.newFolder("replaced").toPath().resolve("x.bin")

        // No version guard exists on the read path today (the HEAD before the GET never made one: the GET carries no If-Match and the
        // provider is not handed the ETag the engine listed), so the bytes are the new ones and the count is the new object's.
        val written = runBlocking { s3.download(path, destination) }
        val requests = snapshot()

        assertEquals(replaced.size.toLong(), written)
        assertContentEquals(replaced, Files.readAllBytes(destination))
        assertEquals(listOf("GET"), requests.map(::label), "replaced: one GET; ${report(requests)}")
    }

    // ── integrity ──

    // The MD5 of a file is not at hand at the PUT (the engine hashes with SHA-256 after the upload), so #570 costs a read of
    // the file or a change in what the engine hands down; this test only states the wire format it must end in.
    @Ignore("enable with #570")
    @Test
    fun `a single-part PUT carries the Content-MD5 of its body`() {
        val local = writeLocal()
        uploadAll(local)
        val requests = snapshot()

        val puts = requests.filter { it.method == HttpMethod.Put }
        val report = report(requests)
        assertEquals(tree.files.size, puts.size, "one PUT per object; $report")
        val wrong =
            puts.filter { it.headers["content-md5"] != Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(it.body)) }.map { it.key!! }
        assertTrue(wrong.isEmpty(), "upload: ${wrong.size} of ${puts.size} PUTs carry no Content-MD5 or a wrong one; ${report(requests, wrong)}")
    }

    private companion object {
        const val SHOWN = 4
    }
}
