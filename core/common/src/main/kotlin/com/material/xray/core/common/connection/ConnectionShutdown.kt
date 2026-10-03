package com.material.xray.core.common.connection

/** Stops the running connection on behalf of code that must not hold it open, such as maintenance. */
interface ConnectionShutdown {
    /**
     * Stops the running connection, if there is one, and waits until it has stopped. Throws when it
     * cannot be stopped or does not stop in time.
     */
    suspend fun disconnectIfRunning()

    /** Asks the runtime to stop the connection at once, without waiting for it to finish. */
    fun forceDisconnect()
}
