package com.material.xray.service

import android.util.Log
import com.material.xray.core.common.log.LogEcho
import com.material.xray.core.common.log.LogSource
import org.koin.core.annotation.Singleton

/** Mirrors the in-app log to logcat, under one tag per source. */
@Singleton(binds = [LogEcho::class])
class LogcatEcho : LogEcho {
    override fun echo(source: LogSource, message: String) {
        when (source) {
            LogSource.APP -> Log.d("MXray", message)
            LogSource.XRAY -> Log.d("MXray.xray", message)
        }
    }
}
