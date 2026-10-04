package com.material.xray.core.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Singleton

@Singleton
class ServerSelectionCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withSelectionLock(block: suspend () -> T): T = mutex.withLock { block() }
}
