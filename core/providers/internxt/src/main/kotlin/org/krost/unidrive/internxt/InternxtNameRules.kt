package org.krost.unidrive.internxt

import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.io.WindowsDeviceNames

/**
 * The names the official Internxt clients accept, for the profile key `internxt_strict_names`: a name is compatible when it
 * passes every one of the rules below, so that what unidrive creates is also usable in the official apps. Behaviour as
 * read in Internxt's public sources (not run), written here from that description:
 *
 * - The drive server (`internxt/drive-server-wip`, master at 21826eb): a folder name with a slash or a backslash, or one
 *   that is empty or whitespace only, is refused on create, rename and move (`folder.usecase.ts`); a file is refused only
 *   when its plain name and its type are both empty or whitespace only (`file.domain.ts`). No length or reserved-name check.
 * - The desktop client (`internxt/drive-desktop`, `src/context/virtual-drive/items/validate-windows-name.ts`): it skips an
 *   item, with the sync issue `INVALID_WINDOWS_NAME`, whose name holds one of `< > : " / \ | ? *`. Release 2.7.0
 *   (`feat/2-7-0-release` at d7512c4) also skips a name that starts or ends with whitespace (any Unicode space); main at
 *   ee8c7b3 instead skips control characters and a trailing space or dot, and lets a leading space pass. Both are applied.
 * - Windows, where the desktop client writes: the device names (`CON`, `NUL.txt`, ..., [WindowsDeviceNames]) and more
 *   than 255 UTF-16 units in one component. Linux: more than 255 UTF-8 bytes in one component.
 */
internal object InternxtNameRules {
    /** The rule a name breaks; [token] follows `name_incompatible: ` in the refusal's reason. */
    enum class Rule(
        val token: String,
    ) {
        EMPTY("empty"),
        SEPARATOR("separator"),
        CONTROL_CHARACTER("control_character"),
        WINDOWS_CHARACTER("windows_character"),
        LEADING_WHITESPACE("leading_whitespace"),
        TRAILING_WHITESPACE("trailing_whitespace"),
        TRAILING_DOT("trailing_dot"),
        DEVICE_NAME("device_name"),
        TOO_LONG_UTF16("too_long_utf16"),
        TOO_LONG_UTF8("too_long_utf8"),
    }

    private const val MAX_UNITS = 255
    private const val MAX_BYTES = 255
    private const val WINDOWS_CHARACTERS = "<>:\"|?*"

    // The whitespace of a JavaScript regular expression's `\s`, the class the desktop client's rule uses.
    private fun isScriptWhitespace(c: Char): Boolean =
        c == ' ' || c in '\u0009'..'\u000D' || c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F' || c == '\u3000' || c == '\uFEFF'

    private fun utf8Bytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** The first rule the one path component [name] breaks, or null when it is compatible. */
    fun issue(name: String): Rule? =
        when {
            name.all(::isScriptWhitespace) -> Rule.EMPTY
            name.any { it == '/' || it == '\\' } -> Rule.SEPARATOR
            name.any { it < ' ' } -> Rule.CONTROL_CHARACTER
            name.any { it in WINDOWS_CHARACTERS } -> Rule.WINDOWS_CHARACTER
            isScriptWhitespace(name.first()) -> Rule.LEADING_WHITESPACE
            isScriptWhitespace(name.last()) -> Rule.TRAILING_WHITESPACE
            name.last() == '.' -> Rule.TRAILING_DOT
            WindowsDeviceNames.isReserved(name) -> Rule.DEVICE_NAME
            name.length > MAX_UNITS -> Rule.TOO_LONG_UTF16
            utf8Bytes(name) > MAX_BYTES -> Rule.TOO_LONG_UTF8
            else -> null
        }

    /** The first rule a component of [path] breaks, or null when all are compatible. The root has no component. */
    fun pathIssue(path: String): Rule? = path.split('/').filter { it.isNotEmpty() }.firstNotNullOfOrNull(::issue)

    /** The [NameRefusedException.reason] of a name that breaks [rule]. */
    fun refusalReason(rule: Rule): String = "${NameRefusedException.NAME_INCOMPATIBLE}: ${rule.token}"

    /**
     * [stem], cut on a code-point boundary so that it and [tail] (everything appended after it: a suffix, a counter, the
     * extension) fit in 255 UTF-8 bytes. A stem that already fits is returned as it is.
     */
    fun fitStem(
        stem: String,
        tail: String,
    ): String {
        val room = (MAX_BYTES - utf8Bytes(tail)).coerceAtLeast(0)
        if (utf8Bytes(stem) <= room) return stem
        var end = 0
        var used = 0
        while (end < stem.length) {
            val codePoint = stem.codePointAt(end)
            val width = Character.charCount(codePoint)
            val bytes = utf8Bytes(stem.substring(end, end + width))
            if (used + bytes > room) break
            used += bytes
            end += width
        }
        return stem.substring(0, end)
    }
}
