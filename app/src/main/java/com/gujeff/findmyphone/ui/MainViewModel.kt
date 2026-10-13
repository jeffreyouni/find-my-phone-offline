package com.gujeff.findmyphone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gujeff.findmyphone.data.Settings
import com.gujeff.findmyphone.service.AppState
import com.gujeff.findmyphone.service.ListenService
import com.gujeff.findmyphone.wake.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings.get()
    val state = AppState.state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun startListening() = ListenService.start(getApplication())
    fun stopListening() = ListenService.stop(getApplication())
    fun testAlarm() = ListenService.testAlarm(getApplication())
    fun stopAlarm() = ListenService.stopAlarm(getApplication())
    fun consumeMessage() { _message.value = null }

    fun update(block: (Settings.Values) -> Settings.Values) = settings.update(block)

    fun importModel(uri: Uri, okText: String, failText: (String) -> String) {
        viewModelScope.launch {
            _message.value = runCatching { withContext(Dispatchers.IO) { ModelManager.importZip(getApplication(), uri) } }
                .fold({ okText }, { failText(it.message ?: it.javaClass.simpleName) })
        }
    }
}
