package com.bydcollector.collector.ha

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HaConnectionState(
    val owned: Boolean = false,
    val activeRoute: HaEndpointProfile? = null,
    val stopping: Boolean = false
)

/** One accepted Start through completed Stop, independent of transport status/backoff. */
class HaConnectionOwnership {
    private val current = MutableStateFlow(HaConnectionState())
    val state = current.asStateFlow()
    val owned: Boolean get() = current.value.owned
    val stopping: Boolean get() = current.value.stopping

    fun reserve(): Boolean = current.compareAndSet(HaConnectionState(), HaConnectionState(owned = true))
    fun publishRoute(route: HaEndpointProfile?) = current.update {
        if (it.owned) it.copy(activeRoute = route) else it
    }
    fun beginStop() = current.update { it.copy(owned = true, stopping = true) }
    fun stopSubmissionFailed() = current.update { it.copy(stopping = false) }
    fun release() { current.value = HaConnectionState() }
}
