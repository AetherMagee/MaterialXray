package com.material.xray.service

import com.material.xray.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    fun update(newState: ConnectionState) {
        _state.value = newState
    }
}
