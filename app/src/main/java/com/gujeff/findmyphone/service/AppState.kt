package com.gujeff.findmyphone.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ListenStatus { OFF, LOADING, LISTENING, WAITING_FOR_LOCK, ALARM, ERROR }

data class UiState(val status: ListenStatus = ListenStatus.OFF, val error: String? = null, val alarmRinging: Boolean = false)

/** Process-wide state shared by the service, alarm and UI. Nothing here is persisted. */
object AppState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun set(status: ListenStatus, error: String? = null) {
        _state.value = _state.value.copy(status = status, error = error)
    }

    fun setAlarm(ringing: Boolean) {
        _state.value = _state.value.copy(alarmRinging = ringing)
    }
}
