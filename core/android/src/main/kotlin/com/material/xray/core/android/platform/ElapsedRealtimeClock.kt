package com.material.xray.core.android.platform

import android.os.SystemClock
import com.material.xray.core.common.platform.MonotonicClock
import org.koin.core.annotation.Singleton

/** [MonotonicClock] on `SystemClock.elapsedRealtimeNanos()`, which keeps counting through deep sleep. */
@Singleton(binds = [MonotonicClock::class])
class ElapsedRealtimeClock : MonotonicClock {
    override fun elapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
