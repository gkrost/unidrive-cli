package org.krost.unidrive.cli

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import org.krost.unidrive.http.UrlRedaction

/**
 * The words `%msg` and `%ex` of the bundled logback.xml. Every provider exception can quote a pre-authenticated
 * URL (Ktor's timeout exceptions do, with the whole query), and a log call anywhere may print its message or
 * its stack trace; cleaning at the layout is the one place that covers all of them. See [UrlRedaction].
 */
class RedactingMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String = UrlRedaction.redact(super.convert(event))
}

class RedactingThrowableConverter : ThrowableProxyConverter() {
    override fun convert(event: ILoggingEvent): String = UrlRedaction.redact(super.convert(event))
}
