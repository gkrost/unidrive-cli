package org.krost.unidrive.io

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * cli#222 item 6: `formatSizePrecise` replaces the three one-decimal formatters of DoctorCommand, RelocateCommand and
 * StatusAudit. The `legacy...` functions below are those three, copied unchanged, as the oracle: the printed text of each
 * command must stay what it was. The canonical truncating `formatSize` is not touched.
 */
class ByteFormatterTest {
    // DoctorCommand.formatBytes, verbatim.
    private fun legacyDoctor(n: Long): String =
        when {
            n >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GiB", n.toDouble() / (1L shl 30))
            n >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", n.toDouble() / (1L shl 20))
            n >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KiB", n.toDouble() / (1L shl 10))
            else -> "$n B"
        }

    // RelocateCommand.formatSize, verbatim.
    private fun legacyRelocate(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var size = bytes.toDouble() / 1024
        var idx = 0
        while (size >= 1024 && idx < units.lastIndex) {
            size /= 1024
            idx++
        }
        return String.format(Locale.ROOT, "%.1f %s", size, units[idx])
    }

    // StatusAudit.formatBytesBinary, verbatim.
    private fun legacyStatusAudit(bytes: Long): String {
        val abs = kotlin.math.abs(bytes)
        return when {
            abs < 1024L -> "$bytes B"
            abs < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
            abs < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    // Every boundary of every unit, one either side of it, plus a seeded spread across the whole range of a Long.
    private val sizes: List<Long> =
        buildList {
            add(0L)
            for (shift in listOf(10, 20, 30, 40, 50)) {
                val unit = 1L shl shift
                for (k in listOf(1L, 2L, 3L, 512L, 1023L, 1024L)) for (d in -1L..1L) add(unit * k + d)
            }
            add(1023L)
            add(1024L)
            add(1_048_575L)
            add(1_048_576L)
            add(1_073_741_823L)
            add(1_073_741_824L)
            add(1_099_511_627_775L)
            add(1_099_511_627_776L)
            add(Long.MAX_VALUE)
            val random = Random(223)
            repeat(2_000) { add(random.nextLong(0, 1L shl random.nextInt(1, 62))) }
        }.filter { it >= 0 }.distinct()

    @Test
    fun `the default prints what DoctorCommand printed, and stops at GiB`() {
        for (n in sizes) assertEquals(legacyDoctor(n), formatSizePrecise(n), "bytes=$n")
    }

    @Test
    fun `with TiB as the largest unit it prints what RelocateCommand printed`() {
        for (n in sizes) assertEquals(legacyRelocate(n), formatSizePrecise(n, ByteUnit.TiB), "bytes=$n")
    }

    @Test
    fun `it prints what StatusAudit printed, a negative value keeps its sign`() {
        for (n in sizes + sizes.map { -it }.filter { it != Long.MIN_VALUE }) assertEquals(legacyStatusAudit(n), formatSizePrecise(n), "bytes=$n")
    }

    @Test
    fun `golden values`() {
        assertEquals("0 B", formatSizePrecise(0))
        assertEquals("1023 B", formatSizePrecise(1023))
        assertEquals("1.0 KiB", formatSizePrecise(1024))
        assertEquals("1.5 KiB", formatSizePrecise(1536))
        assertEquals("1024.0 KiB", formatSizePrecise(1_048_575))
        assertEquals("1.0 MiB", formatSizePrecise(1_048_576))
        assertEquals("953.7 MiB", formatSizePrecise(1_000_000_000))
        assertEquals("372.5 GiB", formatSizePrecise((372.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1024.0 GiB", formatSizePrecise(1L shl 40), "GiB is the largest unit unless TiB is asked for")
        assertEquals("1.0 TiB", formatSizePrecise(1L shl 40, ByteUnit.TiB))
        assertEquals("-1.5 KiB", formatSizePrecise(-1536))
        assertEquals("-1000 B", formatSizePrecise(-1000))
    }

    @Test
    fun `the truncating formatSize is unchanged`() {
        assertEquals("953 MiB", formatSize(1_000_000_000))
        assertEquals("1 KiB", formatSize(1024))
        assertEquals("1023 B", formatSize(1023))
    }
}
