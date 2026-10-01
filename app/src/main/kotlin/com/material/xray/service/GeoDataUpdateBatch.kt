package com.material.xray.service

/** Apply successful updates once all overlapping downloads have released the running core. */
internal class GeoDataUpdateBatch(private val onUpdated: () -> Unit) {
    private val lock = Any()
    private var active = 0
    private var changed = false

    suspend fun run(update: suspend () -> Unit) {
        synchronized(lock) { active++ }
        try {
            update()
            synchronized(lock) { changed = true }
        } finally {
            synchronized(lock) {
                active--
                if (active == 0 && changed) {
                    changed = false
                    onUpdated()
                }
            }
        }
    }
}
