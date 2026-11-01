package org.krost.unidrive.engine

import org.krost.unidrive.io.stagingSiblingName
import org.krost.unidrive.sync.PathNormalizer
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seeded code-point fuzzing of the name rules: names made of random graphic code points (letters, marks, numbers,
 * punctuation, symbols and spaces of every plane the running JDK knows) go into [PathNormalizer.nfc], [localNameIssue]
 * and [stagingSiblingName], and each has to keep its contract:
 * - NFC canonicalisation is the JDK's NFC, idempotent, and gives canonically equivalent spellings one key (archive#171);
 * - a staging name never passes a path component's 255 limit, UTF-8 bytes or UTF-16 units, and a name the host takes
 *   gives a staging name the host takes (archive#529);
 * - a name [localNameIssue] passes can be created on this host and lists back under exactly that name, and a name it
 *   refuses for its length cannot be created (archive#230, archive#526).
 *
 * The seeds are fixed, so a run is repeatable; `UNIDRIVE_FUZZ_SEED` adds one more. Which code points are graphic comes
 * from the JDK's Unicode tables, so a failure names the seed, the JDK and the number of graphic code points it knows.
 */
class LocalNamesFuzzTest {
    @Test
    fun `NFC canonicalisation is the JDK's NFC, idempotent, and one key for every canonically equivalent spelling`() {
        fuzz(PURE_NAMES) { name, fail ->
            val key = PathNormalizer.nfc(name)
            if (key != Normalizer.normalize(name, Normalizer.Form.NFC)) fail("nfc differs from the JDK's NFC")
            if (!Normalizer.isNormalized(key, Normalizer.Form.NFC)) fail("nfc gives a name that is not in NFC")
            if (PathNormalizer.nfc(key) != key) fail("nfc is not idempotent")
            if (PathNormalizer.nfc(Normalizer.normalize(name, Normalizer.Form.NFD)) != key) fail("the NFD spelling has another key")
            if (PathNormalizer.nfc("/a/$name/b") != "/a/$key/b") fail("nfc of a path is not nfc of its segments")
        }
    }

    @Test
    fun `a staging name stays inside the 255 limit and a name the host takes gives a staging name it takes`() {
        var shortened = 0
        fuzz(PURE_NAMES) { name, fail ->
            for (suffix in SUFFIXES) {
                val staging = stagingSiblingName(name, suffix)
                if (staging != name + suffix) shortened++
                val bytes = staging.toByteArray(Charsets.UTF_8).size
                if (bytes > 255) fail("staging name of $bytes UTF-8 bytes with suffix $suffix")
                if (staging.length > 255) fail("staging name of ${staging.length} UTF-16 units with suffix $suffix")
                if (!staging.endsWith(suffix)) fail("staging name loses the suffix $suffix")
                val fits = (name + suffix).toByteArray(Charsets.UTF_8).size <= 255
                if (fits && staging != name + suffix) fail("a name that fits with $suffix is staged under another name")
                for (windows in listOf(true, false)) {
                    if (localNameIssue("/d/$name", windows) == null) {
                        localNameIssue("/d/$staging", windows)?.let { fail("windows=$windows: staging name refused ($it)") }
                    }
                }
            }
        }
        assertTrue(shortened > 0, "the names reach the 255 limit, so some are staged under the short form")
    }

    @Test
    fun `a name localNameIssue passes is created and listed back as it is, and one too long for it cannot be created`() {
        val jnu = System.getProperty("sun.jnu.encoding", "")
        org.junit.Assume.assumeTrue(
            "file names need a UTF-8 file name encoding on this host (sun.jnu.encoding=$jnu)",
            WINDOWS || jnu.equals("UTF-8", ignoreCase = true),
        )
        val dir = Files.createTempDirectory("ud-names-fuzz")
        var n = 0
        var passed = 0
        var tooLong = 0
        try {
            fuzz(HOST_NAMES) { name, fail ->
                val folder = Files.createDirectory(dir.resolve("n${n++}"))
                val issue = localNameIssue("/$name")
                val created = runCatching { Files.createFile(folder.resolve(name)) }
                if (issue == null) {
                    passed++
                    created.exceptionOrNull()?.let { fail("passed by localNameIssue, refused by the host: $it") }
                    val listed = Files.list(folder).use { s -> s.map { it.fileName.toString() }.toList() }
                    if (created.isSuccess && listed != listOf(name)) fail("created, but listed back as ${listed.map(::ascii)}")
                } else if (issue.contains("cannot exceed 255")) {
                    tooLong++
                    val e = created.exceptionOrNull()
                    if (e == null) fail("refused as too long ($issue), but the host created it")
                    if (e != null && e !is FileSystemException && e !is InvalidPathException) fail("unexpected failure $e")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        assertTrue(passed > 0 && tooLong > 0, "the names cover both sides of the limit: $passed passed, $tooLong too long")
    }

    // ── the fuzzer ───────────────────────────────────────────────────────────

    private fun fuzz(
        perSeed: Int,
        check: (name: String, fail: (String) -> Unit) -> Unit,
    ) {
        val failures = mutableListOf<String>()
        println("seeds ${seeds()}, JDK ${Runtime.version()}, ${GRAPHIC.size} graphic code points, windows=$WINDOWS")
        for (seed in seeds()) {
            val random = Random(seed)
            repeat(perSeed) { i ->
                val name = randomName(random)
                check(name) { what -> failures += "seed $seed, name #$i '${ascii(name)}': $what" }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} failures (JDK ${Runtime.version()}, ${GRAPHIC.size} graphic code points, " +
                "windows=$WINDOWS; rerun one seed with UNIDRIVE_FUZZ_SEED):\n" + failures.take(20).joinToString("\n"),
        )
    }

    // Mostly short names with a good share of ASCII punctuation, some long enough to cross the 255 limits (a
    // supplementary code point is 4 UTF-8 bytes and 2 UTF-16 units; long names leave ASCII out, so that a reserved
    // character does not hide the length rule), and a few that end in a dot or a space or are made of dots.
    private fun randomName(random: Random): String {
        val long = random.nextInt(8) == 0
        val length = if (long) random.nextInt(100, 200) else random.nextInt(1, 40)
        val sb = StringBuilder()
        repeat(length) {
            val cp =
                when {
                    long -> NON_ASCII_GRAPHIC[random.nextInt(NON_ASCII_GRAPHIC.size)]
                    random.nextInt(4) == 0 -> ASCII_GRAPHIC[random.nextInt(ASCII_GRAPHIC.size)]
                    else -> GRAPHIC[random.nextInt(GRAPHIC.size)]
                }
            sb.appendCodePoint(cp)
        }
        when (random.nextInt(20)) {
            0 -> sb.append('.')
            1 -> sb.append(' ')
            2 -> return ".".repeat(random.nextInt(1, 5))
        }
        return sb.toString()
    }

    private fun seeds(): List<Long> = FIXED_SEEDS + listOfNotNull(System.getenv("UNIDRIVE_FUZZ_SEED")?.trim()?.toLongOrNull())

    private fun ascii(s: String): String =
        buildString {
            s.codePoints().forEach { cp -> if (cp in 0x21..0x7E) appendCodePoint(cp) else append("{U+%04X}".format(cp)) }
        }

    companion object {
        private val FIXED_SEEDS = listOf(1L, 31L, 255L, 260L, 0x5EEDL)
        private const val PURE_NAMES = 2_000
        private const val HOST_NAMES = 150

        // The suffixes the engine stages with: downloads, the mount's hydration, a parked move.
        private val SUFFIXES = listOf(".unidrive-tmp", ".hydrating-" + "0".repeat(36), "." + "0".repeat(36) + ".tmp")

        private val WINDOWS = System.getProperty("os.name", "").lowercase().contains("win")

        // Graphic code points as Unicode defines them: letters, marks, numbers, punctuation, symbols and space
        // separators, but not '/', which separates names. No controls, format characters, surrogates, private use or
        // unassigned code points.
        private val GRAPHIC_TYPES =
            setOf(
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
                Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.CONNECTOR_PUNCTUATION,
                Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL, Character.SPACE_SEPARATOR,
            ).map { it.toInt() }.toSet()

        private val GRAPHIC: IntArray =
            (0..Character.MAX_CODE_POINT).filter { it != '/'.code && Character.getType(it) in GRAPHIC_TYPES }.toIntArray()

        private val ASCII_GRAPHIC: IntArray = GRAPHIC.filter { it < 0x7F }.toIntArray()

        private val NON_ASCII_GRAPHIC: IntArray = GRAPHIC.filter { it >= 0x80 }.toIntArray()
    }
}
