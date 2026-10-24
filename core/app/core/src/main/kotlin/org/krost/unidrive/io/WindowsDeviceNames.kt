package org.krost.unidrive.io

import java.util.Locale

/**
 * #1090: the one list of file names Windows reserves for devices, and the one rule that applies it. The engine's local
 * name check (`localNameIssue`), the names of the virtual top-level folders (`RootNames`), the profile names
 * (`isValidProfileName`) and OneDrive's pre-upload check all ask here; the Windows client mirrors the same table in
 * `WindowsNames.cs`, so a name one side accepts is not refused by the other.
 *
 * The table is Microsoft's "Naming Files, Paths, and Namespaces": `CON`, `PRN`, `AUX`, `NUL`, `COM0` to `COM9`, `LPT0`
 * to `LPT9`, the superscript forms `COM¹ COM² COM³ LPT¹ LPT² LPT³`, and the console handles `CONIN$` and `CONOUT$` that
 * `CreateFile` opens by name. A reserved name stays reserved with an extension (`NUL.txt`, `nul.tar.gz`).
 *
 * The comparison is on the stem: everything before the first dot, with trailing spaces trimmed first, because Windows
 * drops them when it parses the path (`"NUL .txt"` is the NUL device), case-insensitively. A trailing dot never reaches
 * the stem (it is cut at the first dot). A leading space is part of the name, so `" NUL"` is an ordinary file.
 */
public object WindowsDeviceNames {
    private const val SUPERSCRIPT_DIGITS = "¹²³"

    /** The reserved stems, upper case. */
    public val NAMES: Set<String> =
        buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
            for (device in listOf("COM", "LPT")) {
                for (digit in '0'..'9') add("$device$digit")
                for (digit in SUPERSCRIPT_DIGITS) add("$device$digit")
            }
        }

    /** True when [component] (one path component) is, or starts with the stem of, a reserved device name. */
    public fun isReserved(component: String): Boolean = component.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT) in NAMES
}
