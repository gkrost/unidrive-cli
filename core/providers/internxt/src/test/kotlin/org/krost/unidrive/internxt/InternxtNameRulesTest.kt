package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.InternxtNameRules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The names the official Internxt clients accept: the union of the drive server's folder rule, the desktop client's
 * Windows rule and the portable limits of one path component. One case per rule, each length boundary on both sides.
 */
class InternxtNameRulesTest {
    private val cases: List<Pair<String, Rule?>> =
        listOf(
            // Accepted.
            "report.pdf" to null,
            "a b" to null,
            "CONSOLE.txt" to null,
            "x.y.z" to null,
            ".hidden" to null,
            // The server: empty or whitespace only.
            "" to Rule.EMPTY,
            "   " to Rule.EMPTY,
            "　" to Rule.EMPTY,
            // The server: a slash or a backslash.
            "a/b" to Rule.SEPARATOR,
            "a\\b" to Rule.SEPARATOR,
            // The desktop client and Windows: control characters.
            "a\u0001b" to Rule.CONTROL_CHARACTER,
            "a\tb" to Rule.CONTROL_CHARACTER,
            "\nname" to Rule.CONTROL_CHARACTER,
            // The desktop client and Windows: the reserved characters.
            "a<b" to Rule.WINDOWS_CHARACTER,
            "a>b" to Rule.WINDOWS_CHARACTER,
            "a:b" to Rule.WINDOWS_CHARACTER,
            "a\"b" to Rule.WINDOWS_CHARACTER,
            "a|b" to Rule.WINDOWS_CHARACTER,
            "a?b" to Rule.WINDOWS_CHARACTER,
            "a*b" to Rule.WINDOWS_CHARACTER,
            // The desktop client: whitespace at either end (any Unicode space, not only U+0020).
            " name" to Rule.LEADING_WHITESPACE,
            " name" to Rule.LEADING_WHITESPACE,
            "name " to Rule.TRAILING_WHITESPACE,
            "name " to Rule.TRAILING_WHITESPACE,
            "x " to Rule.TRAILING_WHITESPACE,
            // The desktop client and Windows: a trailing dot.
            "name." to Rule.TRAILING_DOT,
            "." to Rule.TRAILING_DOT,
            ".." to Rule.TRAILING_DOT,
            // Windows: device names, also with an extension, in any case.
            "CON" to Rule.DEVICE_NAME,
            "con.txt" to Rule.DEVICE_NAME,
            "CON.txt" to Rule.DEVICE_NAME,
            "NUL.tar.gz" to Rule.DEVICE_NAME,
            "COM1" to Rule.DEVICE_NAME,
            "lpt9.log" to Rule.DEVICE_NAME,
            // Windows: 255 UTF-16 units per component.
            "a".repeat(255) to null,
            "a".repeat(256) to Rule.TOO_LONG_UTF16,
            "a".repeat(300) to Rule.TOO_LONG_UTF16,
            // Linux: 255 UTF-8 bytes per component; 'é' is one unit and two bytes, an emoji two units and four bytes.
            "é".repeat(127) + "a" to null,
            "é".repeat(128) to Rule.TOO_LONG_UTF8,
            "😀".repeat(63) + "abc" to null,
            "😀".repeat(63) + "abcd" to Rule.TOO_LONG_UTF8,
        )

    @Test
    fun `each rule refuses its own case and a compatible name passes`() {
        val wrong =
            cases.mapNotNull { (name, expected) ->
                val got = InternxtNameRules.issue(name)
                if (got == expected) null else "'${name.take(40)}' (${name.length} units): expected $expected, got $got"
            }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a path is checked component by component and the first failing one names the rule`() {
        assertEquals(null, InternxtNameRules.pathIssue("/"))
        assertEquals(null, InternxtNameRules.pathIssue("/Documents/2026/report.pdf"))
        assertEquals(Rule.WINDOWS_CHARACTER, InternxtNameRules.pathIssue("/Documents/a:b/report.pdf"))
        assertEquals(Rule.DEVICE_NAME, InternxtNameRules.pathIssue("/Documents/CON.txt"))
        assertEquals(Rule.TRAILING_WHITESPACE, InternxtNameRules.pathIssue("/x /y"))
    }

    @Test
    fun `the refusal reason is the name_incompatible token followed by the rule`() {
        assertEquals("name_incompatible: windows_character", InternxtNameRules.refusalReason(Rule.WINDOWS_CHARACTER))
        assertEquals(
            listOf(
                "empty", "separator", "control_character", "windows_character", "leading_whitespace", "trailing_whitespace",
                "trailing_dot", "device_name", "too_long_utf16", "too_long_utf8",
            ),
            Rule.entries.map { it.token },
        )
    }
}
