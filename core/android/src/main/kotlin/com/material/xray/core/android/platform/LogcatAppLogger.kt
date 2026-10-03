package com.material.xray.core.android.platform

import android.util.Log
import com.material.xray.core.common.log.AppLogger
import org.koin.core.annotation.Singleton

/** [AppLogger] on logcat. */
@Singleton(binds = [AppLogger::class])
class LogcatAppLogger : AppLogger {
    override fun log(level: AppLogger.Level, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            AppLogger.Level.DEBUG -> Log.d(tag, message, throwable)
            AppLogger.Level.INFO -> Log.i(tag, message, throwable)
            AppLogger.Level.WARN -> Log.w(tag, message, throwable)
            AppLogger.Level.ERROR -> Log.e(tag, message, throwable)
        }
    }
}
