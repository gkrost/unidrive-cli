package org.krost.unidrive.cli

/** The started appender of `log_file` in config.toml, a redirect of the log beside the default unidrive.log of logback.xml. */
internal fun redirectFileAppender(
    context: ch.qos.logback.classic.LoggerContext,
    logFile: java.nio.file.Path,
): ch.qos.logback.core.rolling.RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> {
    val appender =
        ch.qos.logback.core.rolling
            .RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
    appender.context = context
    appender.name = "FILE"
    appender.file = logFile.toString()
    val policy =
        ch.qos.logback.core.rolling
            .FixedWindowRollingPolicy()
    policy.context = context
    policy.setParent(appender)
    policy.fileNamePattern = "$logFile.%i"
    policy.minIndex = 1
    policy.maxIndex = 3
    policy.start()
    appender.rollingPolicy = policy
    val triggering =
        ch.qos.logback.core.rolling
            .SizeBasedTriggeringPolicy<ch.qos.logback.classic.spi.ILoggingEvent>()
    triggering.context = context
    triggering.setMaxFileSize(
        ch.qos.logback.core.util.FileSize
            .valueOf("10MB"),
    )
    triggering.start()
    appender.triggeringPolicy = triggering
    val encoder =
        ch.qos.logback.classic.encoder
            .PatternLayoutEncoder()
    encoder.context = context
    // UD-251: match logback.xml pattern so redirected logs carry the same
    // build + profile MDC context as the default unidrive.log does.
    encoder.pattern =
        "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{build:-???????}] [%X{profile:-*}] " +
        "[%thread] %logger{36} - %msg%n"
    // #147: UTF-8 like logback.xml's appenders. Without a charset the encoder takes the JVM default, the ANSI code
    // page on Windows under file.encoding=COMPAT, and every character outside it reaches the file as '?'.
    encoder.charset = Charsets.UTF_8
    encoder.start()
    appender.encoder = encoder
    appender.start()
    return appender
}
