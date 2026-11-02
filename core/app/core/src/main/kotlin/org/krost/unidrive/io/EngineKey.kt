package org.krost.unidrive.io

import java.nio.file.Path

/**
 * Engine key of a relative path: `/` plus its name elements joined by `/`.
 *
 * On POSIX `\` is a character of a name and stays inside its element. On Windows the elements are already
 * split on `\`, so the key is the same one a `toString().replace('\\', '/')` used to build. [names] is that
 * element list: a Windows [Path] cannot hold `\` inside one element, and this lives in `:app:core` because a
 * provider (localfs) may depend only on that module.
 */
fun engineKey(names: Iterable<String>): String {
    val joined = names.joinToString("/")
    return if (joined.isEmpty()) "/" else "/$joined"
}

fun engineKey(relative: Path): String = engineKey(relative.map { it.toString() })
