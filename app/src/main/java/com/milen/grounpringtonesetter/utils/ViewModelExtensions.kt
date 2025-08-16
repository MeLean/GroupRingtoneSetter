package com.milen.grounpringtonesetter.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun <T> ViewModel.launchOnIoResultInMain(
    work: suspend CoroutineScope.() -> T,
    onError: (Throwable) -> Unit = {},
    onSuccess: (T) -> Unit = {},
) {
    launch {
        try {
            val data = withContext(Dispatchers.IO) { work() }
            withContext(Dispatchers.Main) { onSuccess(data) }
        } catch (e: Throwable) {
            onError(e)
        }
    }
}

internal fun ViewModel.launch(block: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch {
        block()
    }
