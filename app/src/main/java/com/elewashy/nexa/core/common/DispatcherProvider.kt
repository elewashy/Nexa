package com.elewashy.nexa.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Abstraction over [kotlinx.coroutines.Dispatchers] so background dispatchers can be
 * swapped during testing (e.g. `TestDispatcher` from `kotlinx-coroutines-test`).
 *
 * In production, [DefaultDispatcherProvider] wires each accessor to a real
 * [kotlinx.coroutines.Dispatchers] instance. In tests, a fake implementation can
 * return a single [kotlinx.coroutines.test.TestDispatcher] for deterministic
 * scheduling.
 *
 * Repository / use-case layers should depend on this interface, not on
 * [kotlinx.coroutines.Dispatchers] directly.
 */
interface DispatcherProvider {
    /** For CPU-intensive work (parsing, sorting, filtering). */
    val default: CoroutineDispatcher

    /** For IO-bound work (disk, network). */
    val io: CoroutineDispatcher

    /** The Android main/UI thread. */
    val main: CoroutineDispatcher

    /**
     * Main thread with immediate dispatch when already on it.
     * Prefer this for callbacks that usually run on the main thread to avoid
     * an unnecessary post-to-Looper.
     */
    val mainImmediate: CoroutineDispatcher
}

/** Production implementation delegating to [kotlinx.coroutines.Dispatchers]. */
@Singleton
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
}

/**
 * Hilt qualifiers for directly injecting individual dispatchers when a component
 * only needs one. Prefer [DispatcherProvider] when multiple dispatchers are used.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainImmediateDispatcher
