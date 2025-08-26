package com.milen.grounpringtonesetter.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
    val mainImmediate: CoroutineDispatcher
}

/** Production default mapping to kotlinx.coroutines.Dispatchers. */
object DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
}

/**
 * Global access point so we don’t have to change constructors yet.
 * If/when you add DI later, you can inject DispatcherProvider directly
 * and delete this facade.
 */
object DispatchersProvider : DispatcherProvider {
    @Volatile
    var delegate: DispatcherProvider = DefaultDispatcherProvider
    override val io: CoroutineDispatcher get() = delegate.io
    override val default: CoroutineDispatcher get() = delegate.default
    override val main: CoroutineDispatcher get() = delegate.main
    override val mainImmediate: CoroutineDispatcher get() = delegate.mainImmediate
}
