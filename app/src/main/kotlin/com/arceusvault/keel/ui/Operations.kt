package com.arceusvault.keel.ui

import com.arceusvault.keel.data.Action
import com.arceusvault.keel.data.TrueNasApi
import com.arceusvault.keel.ui.components.describeError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A short message for the snackbar. */
data class Notice(val text: String, val error: Boolean = false)

/**
 * Runs confirmed actions in the app scope, so leaving a screen doesn't cancel a stop or an upgrade
 * halfway through, and reports the outcome as a [Notice].
 */
class Operations(private val scope: CoroutineScope) {
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = _notices

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Keys of actions in flight, so their buttons can show progress and not be pressed twice. */
    val running: StateFlow<Set<String>> = _running

    fun run(api: TrueNasApi, action: Action, onSuccess: () -> Unit = {}) {
        if (action.key in _running.value) return
        _running.update { it + action.key }
        scope.launch {
            try {
                api.perform(action)
                _notices.tryEmit(Notice(action.done))
                onSuccess()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.tryEmit(Notice("${action.title} failed: ${describeError(e)}", error = true))
            } finally {
                _running.update { it - action.key }
            }
        }
    }

    fun notify(text: String, error: Boolean = false) {
        _notices.tryEmit(Notice(text, error))
    }
}
