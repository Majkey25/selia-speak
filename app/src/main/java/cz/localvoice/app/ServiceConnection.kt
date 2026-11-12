package cz.localvoice.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object ServiceConnection {
    private val state = MutableStateFlow(false)
    val connected = state.asStateFlow()

    fun update(connected: Boolean) { state.value = connected }
}
