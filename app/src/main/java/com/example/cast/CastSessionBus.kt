package com.example.cast

import com.example.data.SenderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CastSessionBus {
    private val _senderState = MutableStateFlow<SenderState>(SenderState.Idle)
    val senderState: StateFlow<SenderState> = _senderState.asStateFlow()

    fun updateState(state: SenderState) {
        _senderState.value = state
    }
}
