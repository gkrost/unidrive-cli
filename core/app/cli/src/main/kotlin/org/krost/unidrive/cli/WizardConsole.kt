package org.krost.unidrive.cli

/**
 * The two reads of the interactive `profile add` wizard. [java.io.Console] is sealed since JDK 22 and cannot be scripted, so
 * the wizard reads through this and a test (#845) supplies its own answers; production wraps [System.console].
 */
internal interface WizardConsole {
    fun readLine(): String?

    fun readPassword(prompt: String): CharArray?
}

internal fun java.io.Console.asWizardConsole(): WizardConsole =
    object : WizardConsole {
        override fun readLine(): String? = this@asWizardConsole.readLine()

        override fun readPassword(prompt: String): CharArray? = this@asWizardConsole.readPassword(prompt)
    }
