package com.propdf.core.domain.dispatcher

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Abstraction for coroutine dispatchers.
 * Allows testing with TestDispatcher.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}
