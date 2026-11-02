package org.krost.unidrive.io

import java.util.Locale

/**
 * The host operating system, from `os.name` read at the moment of the call (#223).
 * A cached value would miss a test that sets the property after the class loads.
 * The name is lower-cased with [Locale.ROOT] and matched with [String.startsWith],
 * so `Darwin` is not Windows (a `contains("win")` check would say that it is).
 */
object Os {
    fun isWindows(): Boolean = of(System.getProperty("os.name")).isWindows

    fun isMac(): Boolean = of(System.getProperty("os.name")).isMac

    fun isLinux(): Boolean = of(System.getProperty("os.name")).isLinux

    /** [name] is an `os.name` value, or null when the property is unset. */
    fun of(name: String?): Kind = Kind(name.orEmpty().lowercase(Locale.ROOT))

    class Kind internal constructor(
        private val name: String,
    ) {
        val isWindows: Boolean = name.startsWith("windows")
        val isMac: Boolean = name.startsWith("mac")
        val isLinux: Boolean = name.startsWith("linux")
    }
}
