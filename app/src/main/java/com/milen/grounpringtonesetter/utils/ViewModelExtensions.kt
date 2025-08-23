package com.milen.grounpringtonesetter.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal fun <T> ViewModel.launchOnIoResultInMain(
    work: suspend () -> T,
    onError: (Throwable) -> Unit = {},
    onSuccess: (T) -> Unit = {},
    onFinally: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Job = viewModelScope.launch {
    try {
        val data = withContext(dispatcher) { work() }
        onSuccess(data)
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        onError(e)
    } finally {
        onFinally()
    }
}

internal fun ViewModel.launch(
    coroutineDispatcherProvider: CoroutineDispatcher = Dispatchers.Default,
    block: suspend CoroutineScope.() -> Unit,
): Job =
    viewModelScope.launch(coroutineDispatcherProvider) {
        block()
    }
