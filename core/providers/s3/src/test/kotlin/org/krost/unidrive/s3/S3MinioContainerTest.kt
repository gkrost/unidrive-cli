package org.krost.unidrive.s3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.ScanContext
import org.krost.unidrive.http.RequestAttempt
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The provider against a real S3 server, MinIO in a container, where [S3ProviderTest] has the in-memory [FakeS3]: the
 * listing paged and resumed, uploads single and multipart (resumed after a cut), the part-copy move above 5 GiB, a
 * batch delete the server refuses for one key, a presigned GET, and the conditional writes.
 *
 * Runs in `./gradlew :providers:s3:minioTest`, not in `test` or `check`; without a reachable Docker engine every case
 * is skipped. Each case works in a bucket of its own.
 */
class S3MinioContainerTest {
    companion object {
        // MinIO's own images are no longer published to Docker Hub; this is the community build of the same server,
        // pinned to one release (digest sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372).
        private const val IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z"
        private const val PORT = 9000

        // Throwaway credentials of the container this class starts and removes.
        private const val ROOT_USER = "minio-root"
        private const val ROOT_PASSWORD = "minio-root-secret"

        private var minio: GenericContainer<Nothing>? = null

        @BeforeClass
        @JvmStatic
        fun startMinio() {
            val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            // The CI job that exists to run this suite sets UNIDRIVE_S3_MINIO_REQUIRED: there a missing engine is a failure, not a skip.
            check(docker || System.getenv("UNIDRIVE_S3_MINIO_REQUIRED") != "true") { "UNIDRIVE_S3_MINIO_REQUIRED is set, but no Docker engine is reachable" }
            assumeTrue("No Docker engine is reachable: the MinIO suite is skipped", docker)
            minio =
                GenericContainer<Nothing>(DockerImageName.parse(IMAGE)).apply {
                    withCommand("server", "/data")
                    withEnv("MINIO_ROOT_USER", ROOT_USER)
                    withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
                    // The > 5 GiB object is zeros: compressed, it takes a few MiB of the container's disk, not 10 GiB.
                    withEnv("MINIO_COMPRESSION_ENABLE", "on")
                    withEnv("MINIO_COMPRESSION_EXTENSIONS", ".bin")
                    withExposedPorts(PORT)
                    waitingFor(Wait.forHttp("/minio/health/live").forPort(PORT))
                    start()
                }
            mc("alias", "set", "local", "http://127.0.0.1:$PORT", ROOT_USER, ROOT_PASSWORD)
        }

        @AfterClass
        @JvmStatic
        fun stopMinio() {
            minio?.stop()
            minio = null
        }

        private fun mc(vararg args: String): String {
            val result = minio!!.execInContainer("mc", *args)
            check(result.exitCode == 0) { "mc ${args.first()} ${args.getOrNull(1) ?: ""} failed: ${result.stdout}${result.stderr}" }
            return result.stdout
        }

        private const val MIB = 1024L * 1024
        private val MTIME: Instant = Instant.parse("2026-02-03T04:05:06.123Z")
    }

    private val dir: Path = Files.createTempDirectory("s3-minio")
    private val bucket = "t-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @BeforeTest
    fun createBucket() {
        mc("mb", "local/$bucket")
    }

    @AfterTest
    fun removeLocalFiles() {
        dir.toFile().deleteRecursively()
    }

    private val endpoint: String get() = minio!!.let { "http://${it.host}:${it.getMappedPort(PORT)}" }

    private fun config(
        vendor: S3Config.Vendor? = null,
        accessKey: String = ROOT_USER,
        secretKey: String = ROOT_PASSWORD,
        threshold: Long = S3Config.MULTIPART_THRESHOLD,
        minPart: Long = S3Config.MIN_PART_SIZE,
    ) = S3Config(
        bucket,
        "us-east-1",
        endpoint,
        accessKey,
        secretKey,
        dir,
        multipartThreshold = threshold,
        minPartSize = minPart,
        vendor = vendor ?: S3Config.Vendor.of(endpoint),
    )

    private fun provider(
        vendor: S3Config.Vendor? = null,
        accessKey: String = ROOT_USER,
        secretKey: String = ROOT_PASSWORD,
        threshold: Long = S3Config.MULTIPART_THRESHOLD,
        minPart: Long = S3Config.MIN_PART_SIZE,
    ) = S3Provider(config(vendor, accessKey, secretKey, threshold, minPart))

    private fun api() = S3Api(config())

    private fun local(
        name: String,
        bytes: ByteArray,
    ): Path =
        dir.resolve(name).also {
            Files.write(it, bytes)
            Files.setLastModifiedTime(it, FileTime.from(MTIME))
        }

    // Real I/O: real time, and a bound so a hung server fails the case instead of the build.
    private fun live(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(5 * 60_000L) { block() } }
    }

    private suspend fun text(
        provider: S3Provider,
        path: String,
    ): String {
        val out = dir.resolve("download-" + UUID.randomUUID())
        provider.download(path, out)
        return Files.readString(out)
    }

    private suspend fun keys(api: S3Api): List<String> = api.list("").objects.map { it.key }

    private class CutOff : RuntimeException("the upload was cut off")

    @Test
    fun `delta pages through the bucket, and a listing resumed after its first page ends the same`() =
        live {
            val names = listOf("docs/", "docs/a b.txt", "Grüße/~*.txt", "deep/x/y.txt") + (0 until 1_050).map { "n/%04d".format(it) }
            api().use { api -> names.map { async(Dispatchers.IO) { api.put(it, null) } }.awaitAll() }

            val pages = mutableListOf<Pair<List<CloudItem>, String>>()
            val full = provider().delta(null, scanContext = ScanContext(null, emptyList(), { items, marker -> pages += items to marker }))

            val files = names.filterNot { it.endsWith("/") }.map { "/$it" }.toSet()
            assertEquals(files, full.items.filterNot { it.isFolder }.map { it.path }.toSet())
            assertEquals(setOf("/docs", "/deep", "/deep/x", "/n", "/Grüße"), full.items.filter { it.isFolder }.map { it.path }.toSet())
            assertEquals(listOf(1_000, 54), pages.map { it.first.size }, "two pages of at most 1,000 keys")

            val (firstPage, marker) = pages.first()
            val resumedPages = mutableListOf<String>()
            val resumed = provider().delta(null, scanContext = ScanContext(marker, firstPage, { _, m -> resumedPages += m }))

            assertEquals(full.items.map { it.path to it.isFolder }.toSet(), resumed.items.map { it.path to it.isFolder }.toSet())
            assertEquals(listOf(pages.last().second), resumedPages, "only the second page is listed again")
        }

    @Test
    fun `a small upload keeps its bytes, MD5 and mtime, and reports the time the listing reports`() =
        live {
            val bytes = "hello minio".toByteArray()
            val provider = provider()

            val item = provider.upload(local("g.txt", bytes), "/dir/Grüße ~*.txt")

            assertEquals(S3Api.md5Hex(bytes), item.hash)
            assertEquals(MTIME, api().use { it.head("dir/Grüße ~*.txt") }?.mtime, "x-amz-meta-mtime as written")
            val listed = provider.delta(null).items.single { it.path == "/dir/Grüße ~*.txt" }
            // The engine compares the time it recorded after the upload with the listed one: they must be equal.
            assertEquals(item.modified, listed.modified)
            assertEquals(item.version, listed.version)
            assertEquals("hello minio", text(provider, item.path))
        }

    @Test
    fun `a multipart upload cut off after two parts resumes with the one part MinIO lacks`() =
        live {
            val bytes = ByteArray((12.5 * MIB).toInt()) { (it % 251).toByte() }
            val file = local("big.bin", bytes)
            val partSize = 5 * MIB

            assertFailsWith<CutOff> {
                provider(threshold = partSize, minPart = partSize).upload(file, "/big.bin") { done, _ -> if (done >= 2 * partSize) throw CutOff() }
            }
            val pending = assertNotNull(MultipartStore(dir.resolve("s3-uploads")).find("big.bin"))
            api().use { assertEquals(listOf(1, 2), it.listParts("big.bin", pending.uploadId)?.map { p -> p.number }) }

            val attempts = Collections.synchronizedList(mutableListOf<RequestAttempt>())
            val resumed = provider(threshold = partSize, minPart = partSize).apply { attachRequestMetrics { attempts += it } }
            val item = resumed.upload(file, "/big.bin")

            assertEquals(1, attempts.count { it.method == "PUT" }, "only part 3 is sent")
            assertEquals(1, attempts.count { it.method == "POST" }, "the complete, no second CreateMultipartUpload")
            assertTrue(item.version!!.endsWith("-3"), item.version)
            assertNull(item.hash, "a multipart ETag is no MD5")
            assertEquals(bytes.size.toLong(), item.size)
            api().use {
                assertNull(it.listParts("big.bin", pending.uploadId), "the completed upload is gone")
                assertEquals(MTIME, it.head("big.bin")?.mtime)
            }
            assertNull(MultipartStore(dir.resolve("s3-uploads")).find("big.bin"))
            val out = dir.resolve("out.bin")
            resumed.download("/big.bin", out)
            assertContentEquals(bytes, Files.readAllBytes(out))
        }

    @Test
    fun `an object above 5 GiB is moved by part copies and keeps its mtime`() =
        live {
            // Built server-side from a 64 MiB seed, so neither side sends or stores 5 GiB.
            val seedSize = 64 * MIB
            val seedParts = 81
            val size = seedSize * seedParts
            assertTrue(size > S3Config.MAX_SINGLE_COPY)
            api().use { api ->
                api.put("seed.bin", local("seed.bin", ByteArray(seedSize.toInt())))
                val uploadId = api.createMultipart("huge.bin", mapOf("mtime" to S3Api.epochText(MTIME)))
                val parts = (1..seedParts).map { it to api.uploadPartCopy("huge.bin", uploadId, it, "seed.bin", 0..<seedSize) }
                api.completeMultipart("huge.bin", uploadId, parts)
            }

            val moved = provider().move("/huge.bin", "/moved/huge.bin")

            assertEquals(size, moved.size)
            // Parts of 512 MiB: eleven for 5,184 MiB.
            assertTrue(moved.version!!.endsWith("-11"), moved.version)
            api().use {
                assertEquals(MTIME, it.head("moved/huge.bin")?.mtime)
                assertNull(it.head("huge.bin"))
            }
        }

    @Test
    fun `a folder delete that the server refuses for one key fails and leaves only that key`() =
        live {
            api().use { api -> listOf("docs/a.txt", "docs/locked.txt", "docs/sub/b.txt", "keep.txt").forEach { api.put(it, null) } }
            val (user, secret) = userWithoutDeleteOf("docs/locked.txt")

            val e = assertFailsWith<ProviderException> { provider(accessKey = user, secretKey = secret).delete("/docs") }

            assertTrue("1 of 3" in (e.message ?: ""), e.message)
            assertEquals(listOf("docs/locked.txt", "keep.txt"), api().use { keys(it) })
        }

    // A user of this bucket who may do anything in it but delete [key].
    private fun userWithoutDeleteOf(key: String): Pair<String, String> {
        val user = "u-$bucket"
        val secret = "s-$bucket"
        val policy =
            """
            {"Version": "2012-10-17", "Statement": [
              {"Effect": "Allow", "Action": ["s3:*"], "Resource": ["arn:aws:s3:::$bucket", "arn:aws:s3:::$bucket/*"]},
              {"Effect": "Deny", "Action": ["s3:DeleteObject"], "Resource": ["arn:aws:s3:::$bucket/$key"]}
            ]}
            """.trimIndent()
        minio!!.copyFileToContainer(Transferable.of(policy), "/tmp/$bucket.json")
        mc("admin", "user", "add", "local", user, secret)
        mc("admin", "policy", "create", "local", bucket, "/tmp/$bucket.json")
        mc("admin", "policy", "attach", "local", bucket, "--user", user)
        return user to secret
    }

    @Test
    fun `a presigned link serves the object without keys, and a link with an altered expiry is refused`() =
        live {
            val bytes = "shared".toByteArray()
            val provider = provider()
            provider.upload(local("s.txt", bytes), "/shared/a b.txt")

            val url = (provider.share("/shared/a b.txt", expiryHours = 1) as CapabilityResult.Success).value

            val http = HttpClient.newHttpClient()
            suspend fun get(link: String) = withContext(Dispatchers.IO) { http.send(HttpRequest.newBuilder(URI(link)).build(), HttpResponse.BodyHandlers.ofByteArray()) }
            val ok = get(url)
            assertEquals(200, ok.statusCode())
            assertContentEquals(bytes, ok.body())
            assertTrue("X-Amz-Expires=3600" in url, url)
            assertEquals(403, get(url.replace("X-Amz-Expires=3600", "X-Amz-Expires=7200")).statusCode())
        }

    @Test
    fun `where the vendor honours If-Match a stale ETag is refused by the server, and a create on a taken key too`() =
        live {
            val attempts = Collections.synchronizedList(mutableListOf<RequestAttempt>())
            val ifMatch = provider(vendor = S3Config.Vendor("aws", 10_000, conditionalWrites = true)).apply { attachRequestMetrics { attempts += it } }
            val first = ifMatch.upload(local("a.txt", "one".toByteArray()), "/a.txt")
            val second = ifMatch.upload(local("a.txt", "two".toByteArray()), "/a.txt", existingRemoteId = first.id, ifMatchETag = first.version)

            attempts.clear()
            assertFailsWith<RemoteConflictException> {
                ifMatch.upload(local("a.txt", "three".toByteArray()), "/a.txt", existingRemoteId = first.id, ifMatchETag = first.version)
            }
            assertEquals(listOf("PUT"), attempts.map { it.method }, "the PUT's If-Match, no HEAD comparison")

            attempts.clear()
            assertFailsWith<RemoteConflictException> { ifMatch.upload(local("a.txt", "four".toByteArray()), "/a.txt") }
            assertEquals(listOf("PUT"), attempts.map { it.method }, "the PUT's If-None-Match, no HEAD comparison")

            val big = local("big.bin", ByteArray((6 * MIB).toInt()) { 1 })
            assertFailsWith<RemoteConflictException> {
                provider(vendor = S3Config.Vendor("aws", 10_000, conditionalWrites = true), threshold = 5 * MIB, minPart = 5 * MIB)
                    .upload(big, "/a.txt", existingRemoteId = first.id, ifMatchETag = first.version)
            }

            // Elsewhere the HEAD comparison stands in for If-Match, against the same server.
            assertFailsWith<RemoteConflictException> {
                provider().upload(local("a.txt", "five".toByteArray()), "/a.txt", existingRemoteId = first.id, ifMatchETag = first.version)
            }
            assertEquals("two", text(provider(), "/a.txt"))
            assertEquals(second.version, api().use { it.head("a.txt") }?.etag)
        }
}
