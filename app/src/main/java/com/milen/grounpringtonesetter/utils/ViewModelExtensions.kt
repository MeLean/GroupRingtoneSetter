package com.milen.grounpringtonesetter.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun ViewModel.launch(
    coroutineDispatcherProvider: CoroutineDispatcher = Dispatchers.Default,
    block: suspend CoroutineScope.() -> Unit,
): Job =
    viewModelScope.launch(coroutineDispatcherProvider) {
        block()
    }
