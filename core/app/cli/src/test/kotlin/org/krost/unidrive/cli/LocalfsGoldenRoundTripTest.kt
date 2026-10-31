package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assume.assumeTrue
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The golden unicode tree (`scripts/golden/unicode-tree/layout.json`) taken through the mirror engine and back on the
 * `localfs` provider: build the tree locally, sync it up, delete the local tree and state.db, sync it down into the
 * empty folder with a fresh state.db, and compare every file's size and SHA-256 with what was written. Exactly: a
 * download that brings back fewer (or more) bytes than went up is a finding (archive#536 was a truncated download the
 * older harness let through because it accepted larger and smaller files alike).
 *
 * The test writes the tree itself, with the same bytes as `generate.ps1`, and first checks them against the committed
 * `manifest.tsv`, so the tree it round-trips is the golden tree and not an approximation of it. Swapping the provider
 * for another one makes this a parity run.
 *
 * Two kinds of names do not come back as they went up, by decision, and are asserted as such rather than skipped:
 * - names that are one name under NFC (the NFC/NFD twins, U+00C5 next to the angstrom sign): the engine syncs neither
 *   member of such a pair (#24, option 1), so neither reaches the remote and neither comes back;
 * - a lone name that is not in NFC is stored and comes back in NFC (archive#171), with its bytes unchanged.
 */
class LocalfsGoldenRoundTripTest {
    private lateinit var base: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private lateinit var profileDir: Path
    private val reporter = CountingReporter()

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-golden")
        local = Files.createDirectories(base.resolve("local"))
        remote = Files.createDirectories(base.resolve("remote"))
        profileDir = Files.createDirectories(base.resolve("profile"))
    }

    @AfterTest
    fun tearDown() {
        runCatching { base.toFile().deleteRecursively() }
    }

    @Test
    fun `the golden tree comes back from the localfs remote with exact sizes and hashes`() {
        val tree = goldenTree().write(local)
        val expected = expectedAfterRoundTrip(tree)

        sync(LocalFsProvider(remote))
        val upFindings = compare(expected.files, expected.dirs, remote)
        assertTrue(upFindings.isEmpty(), "the remote after the upload:\n${upFindings.joinToString("\n")}")

        // Everything local goes: the tree and the state.db.
        local.toFile().deleteRecursively()
        Files.createDirectories(local)
        Files.delete(profileDir.resolve("state.db"))

        sync(LocalFsProvider(remote))
        val downFindings = compare(expected.files, expected.dirs, local)
        assertTrue(downFindings.isEmpty(), "the local tree after the download:\n${downFindings.joinToString("\n")}")

        // A sample through `unidrive get`, into a second profile that has never synced: same bytes again.
        val sample = getSample(expected.files.keys)
        for ((path, bytes) in sample) {
            assertEquals(expected.files.getValue(path).size, bytes.size, "get $path: size")
            assertEquals(sha256(expected.files.getValue(path)), sha256(bytes), "get $path: SHA-256")
        }
    }

    // The red-first proof of the comparison: the same round trip through a provider whose download of one file comes
    // back one byte short, without saying so. The check has to name that file.
    @Test
    fun `a download one byte short does not pass the comparison`() {
        // The cjk group is enough here (it holds the largest file), and keeps this test short.
        val tree = goldenTree().only("cjk/").write(local)
        val expected = Expected(tree.files, tree.dirs)
        val victim = expected.files.keys.first { it.endsWith("数据-1MB.bin") }

        sync(LocalFsProvider(remote))
        local.toFile().deleteRecursively()
        Files.createDirectories(local)
        Files.delete(profileDir.resolve("state.db"))

        runCatching { sync(TruncatingProvider(LocalFsProvider(remote), victim), failuresAllowed = true) }
        val findings = compare(expected.files, expected.dirs, local)

        assertTrue(findings.any { it.contains(ascii(victim)) }, "the short download of ${ascii(victim)} is a finding: $findings")
        assertEquals(1, findings.size, "and the only one: $findings")
    }

    // The engine itself has no second line against such a download: it places the short file, records the row as
    // hydrated and never repairs it (#282). Expected once that is fixed: the action fails and no short file is left
    // behind as synced.
    @Ignore("#282: the mirror places a short download and records it as hydrated")
    @Test
    fun `the mirror refuses a download shorter than the item`() {
        val tree = goldenTree().only("cjk/").write(local)
        val victim = tree.files.keys.first { it.endsWith("数据-1MB.bin") }
        sync(LocalFsProvider(remote))
        local.toFile().deleteRecursively()
        Files.createDirectories(local)
        Files.delete(profileDir.resolve("state.db"))

        runCatching { sync(TruncatingProvider(LocalFsProvider(remote), victim), failuresAllowed = true) }

        assertTrue(reporter.failed > 0, "the short download fails its action; warnings: ${reporter.warnings}")
        val db = StateDatabase(profileDir.resolve("state.db")).also { it.initialize() }
        val row = db.getEntry("/$victim").also { db.close() }
        assertTrue(row == null || !row.isHydrated || row.localSize == row.remoteSize, "no short file is recorded as synced: $row")
    }

    // ── what the round trip must give ─────────────────────────────────────────

    private class Expected(
        val files: Map<String, ByteArray>,
        val dirs: Set<String>,
    )

    private fun expectedAfterRoundTrip(tree: GoldenTree): Expected {
        val byNfc = tree.files.keys.groupBy { nfc(it) }
        val twins = byNfc.values.filter { it.size > 1 }.flatten().toSet()
        assertTrue(twins.isNotEmpty(), "the golden tree holds NFC twin pairs")
        val files =
            tree.files
                .filterKeys { it !in twins }
                .mapKeys { (path, _) -> nfc(path) }
        val dirs = tree.dirs.map { nfc(it) }.toSet()
        return Expected(files.toSortedMap(), dirs.toSortedSet())
    }

    /** Every difference between the expected tree and the tree under [root], one line each; empty when they agree. */
    private fun compare(
        files: Map<String, ByteArray>,
        dirs: Set<String>,
        root: Path,
    ): List<String> {
        val actualFiles = mutableMapOf<String, Path>()
        val actualDirs = mutableSetOf<String>()
        Files.walk(root).use { stream ->
            stream.filter { it != root }.forEach { path ->
                val rel = root.relativize(path).joinToString("/")
                if (Files.isDirectory(path)) actualDirs += rel else actualFiles[rel] = path
            }
        }
        val findings = mutableListOf<String>()
        for ((path, bytes) in files) {
            val actual = actualFiles[path]
            if (actual == null) {
                findings += "MISSING ${ascii(path)}"
                continue
            }
            val size = Files.size(actual)
            if (size != bytes.size.toLong()) {
                findings += "SIZE ${ascii(path)}: expected ${bytes.size}, got $size"
                continue
            }
            if (sha256(Files.readAllBytes(actual)) != sha256(bytes)) findings += "HASH ${ascii(path)}"
        }
        for (path in actualFiles.keys - files.keys) findings += "EXTRA ${ascii(path)}"
        for (dir in dirs - actualDirs) findings += "MISSING DIR ${ascii(dir)}"
        for (dir in actualDirs - dirs) findings += "EXTRA DIR ${ascii(dir)}"
        return findings
    }

    // ── the engine and the CLI ───────────────────────────────────────────────

    private fun sync(
        provider: CloudProvider,
        failuresAllowed: Boolean = false,
    ) {
        val db = StateDatabase(profileDir.resolve("state.db")).also { it.initialize() }
        try {
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = local,
                    reporter = reporter,
                    cacheRoot = Files.createDirectories(base.resolve("cache")),
                )
            reporter.reset()
            runBlocking {
                provider.authenticate()
                engine.syncOnce()
            }
        } finally {
            db.close()
        }
        if (!failuresAllowed) assertEquals(0, reporter.failed, "no action failed; warnings: ${reporter.warnings}")
    }

    // `unidrive get` of a few files that stress the path handling (plane-2 ideographs, emoji folders, the largest
    // file, a NUL-heavy file) into a profile with an empty sync root and no state.db.
    private fun getSample(paths: Set<String>): Map<String, ByteArray> {
        val sample =
            listOf("数据-1MB.bin", "𠮷野家.txt", "nul-bytes.bin", "🐱 empty.txt", "deepest.txt").map { name ->
                paths.single { it.endsWith("/$name") }
            } + paths.first { it.startsWith("cats/") && it.count { c -> c == '/' } >= 5 }
        val configDir = Files.createDirectories(base.resolve("get-config"))
        val getRoot = Files.createDirectories(base.resolve("get-local"))
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.golden]
            |type = "localfs"
            |sync_root = "${toml(getRoot)}"
            |root_path = "${toml(remote)}"
            """.trimMargin() + "\n",
        )
        val out = ByteArrayOutputStream()
        val originalOut = System.out
        val result = mutableMapOf<String, ByteArray>()
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            for (path in sample) {
                val exit = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "golden", "get", "/$path")
                assertEquals(0, exit, "get /${ascii(path)} exits 0; output:\n$out")
                result[path] = Files.readAllBytes(getRoot.resolve(path))
            }
        } finally {
            System.setOut(originalOut)
        }
        return result
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    // A provider that hands back one file a byte short and reports the short count as if it were the whole file.
    private class TruncatingProvider(
        private val inner: CloudProvider,
        private val victim: String,
    ) : CloudProvider by inner {
        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            val n = inner.download(remotePath, destination)
            if (remotePath.trimStart('/') != victim) return n
            FileChannel.open(destination, StandardOpenOption.WRITE).use { it.truncate(n - 1) }
            return n - 1
        }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long = download(remotePath, destination)

        override suspend fun downloadWithHash(
            remotePath: String,
            destination: Path,
            targetMtimeMillis: Long?,
        ): DownloadReceipt = DownloadReceipt(download(remotePath, destination))

        override suspend fun downloadByIdWithHash(
            remoteId: String,
            remotePath: String,
            destination: Path,
            targetMtimeMillis: Long?,
        ): DownloadReceipt = DownloadReceipt(download(remotePath, destination))
    }

    private class CountingReporter : ProgressReporter {
        @Volatile var failed = 0
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun reset() {
            failed = 0
            warnings.clear()
        }

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            this.failed = failed
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    // ── the golden tree, as generate.ps1 builds it ───────────────────────────

    private class GoldenTree(
        val files: Map<String, ByteArray>,
        val dirs: Set<String>,
        private val mtimes: Map<String, Instant>,
    ) {
        /** The part of the tree below [prefix]. */
        fun only(prefix: String): GoldenTree =
            GoldenTree(
                files.filterKeys { it.startsWith(prefix) },
                dirs.filter { "$it/".startsWith(prefix) }.toSet(),
                mtimes,
            )

        /** Writes the tree under [root] and returns it. */
        fun write(root: Path): GoldenTree {
            for (dir in dirs) Files.createDirectories(root.resolve(dir))
            for ((path, bytes) in files) {
                val file = root.resolve(path)
                Files.write(file, bytes)
                Files.setLastModifiedTime(file, FileTime.from(mtimes.getValue(path)))
            }
            return this
        }
    }

    private fun goldenTree(): GoldenTree {
        val folder = goldenFolder()
        assumeTrue("the golden tree (scripts/golden/unicode-tree) is not in this checkout", folder != null)
        val spec = Json.parseToJsonElement(Files.readString(folder!!.resolve("layout.json"))).jsonObject
        val id = spec.getValue("id").jsonPrimitive.content
        val defaultMtime = Instant.parse("2026-01-01T12:00:00Z")
        val dirs = sortedSetOf<String>()
        val files = sortedMapOf<String, ByteArray>()
        val mtimes = mutableMapOf<String, Instant>()

        fun addDir(parts: List<String>) {
            for (n in 1..parts.size) dirs += parts.take(n).joinToString("/")
        }

        for (node in spec.getValue("nodes").jsonArray.map { it.jsonObject }) {
            val dirParts = node.getValue("dir").jsonArray.map { expandTokens(it.jsonPrimitive.content) }
            addDir(dirParts)
            (node["emptyDirs"] as? JsonArray)?.forEach { addDir(dirParts + expandTokens(it.jsonPrimitive.content)) }
            (node["files"] as? JsonArray)?.forEach { element ->
                val f = element.jsonObject
                val name = applyForm(expandTokens(f.string("name")!!), f.string("nf"))
                val rel = (dirParts + name).joinToString("/")
                val bytes = f["bytes"]?.jsonPrimitive?.longOrNull
                files[rel] =
                    if (bytes != null) {
                        pseudoRandomBytes(bytes.toInt(), f.string("seed") ?: "$id|$rel", f.flag("zeros"))
                    } else {
                        encodeText(f, id, rel)
                    }
                mtimes[rel] = f.string("mtime")?.let { Instant.parse(it) } ?: defaultMtime
            }
        }
        assertMatchesManifest(folder.resolve("manifest.tsv"), files, dirs)
        return GoldenTree(files, dirs, mtimes)
    }

    // The tree this test writes is the committed one, byte for byte: same paths, sizes and SHA-256 as manifest.tsv.
    private fun assertMatchesManifest(
        manifest: Path,
        files: Map<String, ByteArray>,
        dirs: Set<String>,
    ) {
        val rows = Files.readAllLines(manifest, Charsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
        val manifestDirs = rows.filter { it[0] == "D" }.map { it[6] }.toSortedSet()
        val manifestFiles = rows.filter { it[0] == "F" }.associate { it[6] to (it[1].toInt() to it[2]) }
        assertEquals(manifestDirs, dirs.toSortedSet(), "the directories built from layout.json are those of manifest.tsv")
        assertEquals(manifestFiles.keys.toSortedSet(), files.keys.toSortedSet(), "the files built from layout.json are those of manifest.tsv")
        for ((path, bytes) in files) {
            assertEquals(manifestFiles.getValue(path), bytes.size to sha256(bytes), "size and SHA-256 of ${ascii(path)} as in manifest.tsv")
        }
    }

    private fun goldenFolder(): Path? {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("scripts/golden/unicode-tree")
            if (Files.isRegularFile(candidate.resolve("layout.json"))) return candidate
            dir = dir.parent
        }
        return null
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun expandTokens(s: String): String {
        val codePoints = Regex("""\{U\+([0-9A-Fa-f]{4,6})\}""").replace(s) { Character.toString(it.groupValues[1].toInt(16)) }
        return Regex("""\{rep:([^:}]+):(\d+)\}""").replace(codePoints) { it.groupValues[1].repeat(it.groupValues[2].toInt()) }
    }

    private fun applyForm(
        name: String,
        nf: String?,
    ): String =
        when (nf) {
            "NFC" -> Normalizer.normalize(name, Normalizer.Form.NFC)
            "NFD" -> Normalizer.normalize(name, Normalizer.Form.NFD)
            else -> name
        }

    // SHA-256 counter mode: block i is SHA-256("<seed>|<i>"); with zeros, every even byte is 0.
    private fun pseudoRandomBytes(
        count: Int,
        seed: String,
        zeros: Boolean,
    ): ByteArray {
        val buf = ByteArray(count)
        var pos = 0
        var i = 0
        while (pos < count) {
            val h = MessageDigest.getInstance("SHA-256").digest("$seed|$i".toByteArray(Charsets.UTF_8))
            val n = minOf(32, count - pos)
            h.copyInto(buf, pos, 0, n)
            pos += n
            i++
        }
        if (zeros) for (k in 0 until count step 2) buf[k] = 0
        return buf
    }

    private fun encodeText(
        f: JsonObject,
        id: String,
        rel: String,
    ): ByteArray {
        var body = f.string("text") ?: ""
        if (!f.flag("raw")) body += "\n"
        if (!f.flag("plain")) body += "$id | $rel\n"
        if (f.string("eol") == "crlf") body = body.replace("\n", "\r\n")
        return when (f.string("enc")) {
            "utf8bom" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(Charsets.UTF_8)
            "utf16le" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE)
            "utf16be" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body.toByteArray(Charsets.UTF_16BE)
            "latin1" -> body.toByteArray(Charsets.ISO_8859_1)
            else -> body.toByteArray(Charsets.UTF_8)
        }
    }

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    // Failure messages stay ASCII, the way manifest.tsv's path_ascii column spells a path.
    private fun ascii(s: String): String =
        buildString {
            s.codePoints().forEach { cp -> if (cp in 0x20..0x7E) appendCodePoint(cp) else append("{U+%04X}".format(cp)) }
        }
}
