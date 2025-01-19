package com.milen.grounpringtonesetter.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun <T> ViewModel.launchOnIoResultInMain(
    work: () -> T,
    onError: (Throwable) -> Unit = {},
    onSuccess: (T) -> Unit = {},
) {
    viewModelScope.launch {
        try {
            val data = withContext(Dispatchers.IO) { work() }
            withContext(Dispatchers.Main) { onSuccess(data) }
        } catch (e: Throwable) {
            onError(e)
        }
    }
}