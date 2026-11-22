package org.krost.unidrive.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import java.nio.file.Path

/**
 * gkrost/unidrive-cli#897: a logback RollingFileAppender is not multi-process safe, so a rolling log has exactly one
 * writing process. A long-running process (`daemon run`, `sync`) takes `unidrive-<profile>.log` for itself once its
 * profile is known and leaves the shared, prudent `unidrive.log` of logback.xml, which every other process keeps writing.
 * A profile has one daemon (the profile lock), so the file's name is stable per profile and its writer is unique.
 */
internal object LogOwnership {
    const val SHARED_APPENDER = "FILE"
    const val OWNED_APPENDER = "OWNED_FILE"

    /** `unidrive-<profile>.log`; a profile name is a config key, but nothing here lets one climb out of the log folder. */
    fun ownedFileName(profile: String): String = "unidrive-" + profile.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".log"

    /** The started appender that rolls `<logDir>/unidrive-<profile>.log` by size and day, five files, 50 MB in all. */
    fun ownedAppender(
        context: LoggerContext,
        logDir: Path,
        profile: String,
        maxFileSize: String = "10MB",
        retention: Boolean = true,
    ): RollingFileAppender<ILoggingEvent> {
        val file = logDir.resolve(ownedFileName(profile))
        val appender = RollingFileAppender<ILoggingEvent>()
        appender.context = context
        appender.name = OWNED_APPENDER
        appender.file = file.toString()
        val policy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>()
        policy.context = context
        policy.setParent(appender)
        policy.fileNamePattern = file.toString().removeSuffix(".log") + ".%d{yyyy-MM-dd}.%i.log"
        policy.setMaxFileSize(FileSize.valueOf(maxFileSize))
        if (retention) {
            policy.maxHistory = 5
            policy.setTotalSizeCap(FileSize.valueOf("50MB"))
        }
        policy.start()
        appender.rollingPolicy = policy
        appender.encoder = sharedEncoder(context)
        appender.start()
        return appender
    }

    /** The shared appender's own pattern and charset, so a line reads the same in either file. */
    private fun sharedEncoder(context: LoggerContext): ch.qos.logback.core.encoder.Encoder<ILoggingEvent> {
        val shared = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(SHARED_APPENDER) as? RollingFileAppender<ILoggingEvent>
        val pattern =
            (shared?.encoder as? ch.qos.logback.core.encoder.LayoutWrappingEncoder<ILoggingEvent>)
                ?.layout
                ?.let { it as? ch.qos.logback.classic.PatternLayout }
                ?.pattern
                ?: "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{build:--------}] [%X{profile:-*}] [%X{scan:--------}] [%thread] %logger{36} - %msg%n%ex"
        val encoder = ch.qos.logback.classic.encoder.PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = pattern
        encoder.charset = Charsets.UTF_8
        encoder.start()
        return encoder
    }

    /** Moves the root logger from the shared file to the profile's own: attach the owned appender first, so no line falls between. */
    fun claim(
        context: LoggerContext,
        logDir: Path,
        profile: String,
    ) {
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.getAppender(OWNED_APPENDER) != null) return
        root.addAppender(ownedAppender(context, logDir, profile))
        root.getAppender(SHARED_APPENDER)?.let {
            root.detachAppender(it)
            it.stop()
        }
    }

    /** [claim] for the running process, at the folder logback.xml writes to; a process without logback (tests) is left alone. */
    fun claim(profile: String) {
        val context = org.slf4j.LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        // A test JVM logs to the console only (logback-test.xml): there is no shared appender, so there is nothing to leave.
        if (context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(SHARED_APPENDER) == null) return
        claim(context, StoragePermissions.defaultLogDir(), profile)
    }
}
