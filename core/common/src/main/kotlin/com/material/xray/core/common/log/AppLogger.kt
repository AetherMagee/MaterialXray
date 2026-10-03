package com.material.xray.core.common.log

import java.io.PrintStream

/**
 * A platform log, shaped like `android.util.Log` so a call such as `Log.w(TAG, "failed", error)`
 * becomes `logger.w(TAG, "failed", error)`.
 *
 * Platform-free modules take an [AppLogger] instead of calling `android.util.Log`. On Android,
 * `:core:android` provides `LogcatAppLogger` and binds it as the [AppLogger] singleton; JVM hosts
 * and tests use [PrintAppLogger] or [NoOpAppLogger].
 *
 * This is the developer log (logcat). The in-app log users see is [LogBuffer].
 */
fun interface AppLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, tag: String, message: String, throwable: Throwable?)

    fun d(tag: String, message: String, throwable: Throwable? = null) = log(Level.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) = log(Level.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)
}

/** Logs nothing. */
object NoOpAppLogger : AppLogger {
    override fun log(level: AppLogger.Level, tag: String, message: String, throwable: Throwable?) = Unit
}

/**
 * Prints logcat-style lines (`W/Tag: message`) and stack traces to [out], for JVM hosts and tests.
 */
class PrintAppLogger(private val out: PrintStream = System.out) : AppLogger {
    override fun log(level: AppLogger.Level, tag: String, message: String, throwable: Throwable?) {
        synchronized(out) {
            out.println("${level.name.first()}/$tag: $message")
            throwable?.printStackTrace(out)
        }
    }
}
