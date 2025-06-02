package moe.reimu

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import java.io.File

class LoggerNameFilter(private val startsWith: String, private val include: Boolean) : ch.qos.logback.core.filter.Filter<ch.qos.logback.classic.spi.ILoggingEvent>() {
    override fun decide(event: ch.qos.logback.classic.spi.ILoggingEvent): FilterReply {
        return if (event.loggerName.startsWith(startsWith)) {
            if (include) {
                FilterReply.ACCEPT
            } else {
                FilterReply.DENY
            }
        } else {
            if (include) {
                FilterReply.DENY
            } else {
                FilterReply.ACCEPT
            }
        }
    }
}

fun configureLogging(directory: File) {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext

    // Console Appender
    val consoleAppender = ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
        this.context = context
        this.encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
            this.start()
        }
        this.start()
    }

    // File Appender
    val fileAppender = FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
        this.context = context
        this.file = File(directory, "default.log").absolutePath
        this.encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
            this.start()
        }
        addFilter(LoggerNameFilter("jadx.", false).apply { start() })
        this.isAppend = false
        this.start()
    }

    // Jadx Appender
    val jadxAppender = FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
        this.context = context
        this.file = File(directory, "jadx.log").absolutePath
        this.encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
            this.start()
        }
        addFilter(LoggerNameFilter("jadx.", true).apply { start() })
        this.isAppend = false
        this.start()
    }

    // Set up the root logger with both appenders
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    rootLogger.detachAndStopAllAppenders()  // Clear any existing appenders
    rootLogger.addAppender(consoleAppender)
    rootLogger.addAppender(fileAppender)
    rootLogger.addAppender(jadxAppender)
    rootLogger.level = ch.qos.logback.classic.Level.INFO  // Set log level
}