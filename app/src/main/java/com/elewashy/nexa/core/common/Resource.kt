package com.elewashy.nexa.core.common

/**
 * Uniform result envelope for async operations crossing the data → domain → UI layers.
 *
 * Use this in repository / use-case return types instead of ad-hoc `try/catch`
 * scattered across the codebase. Upstream layers may throw; the boundary layer
 * (usually a repository) wraps the outcome in [Resource] so presentation code
 * handles success/error/loading uniformly.
 *
 * Example:
 * ```
 * fun observeUpdate(): Flow<Resource<UpdateResponse>> = flow {
 *     emit(Resource.Loading)
 *     emit(
 *         runCatching { api.fetchUpdate() }
 *             .fold(
 *                 onSuccess = { Resource.Success(it) },
 *                 onFailure = { Resource.Error(it) }
 *             )
 *     )
 * }
 * ```
 *
 * `Resource` is covariant in `T` so `Resource<Child>` can be used where
 * `Resource<Parent>` is expected.
 */
sealed class Resource<out T> {

    /** Operation succeeded; [data] is the produced value. */
    data class Success<out T>(val data: T) : Resource<T>()

    /**
     * Operation failed.
     *
     * @property throwable root cause (never null — always wrap).
     * @property message optional user-readable message; defaults to [Throwable.message].
     */
    data class Error(
        val throwable: Throwable,
        val message: String? = throwable.message
    ) : Resource<Nothing>()

    /** Operation is in flight. Emit before network/IO work begins. */
    data object Loading : Resource<Nothing>()

    /** Convenience: `true` when this is [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** Convenience: `true` when this is [Error]. */
    val isError: Boolean get() = this is Error

    /** Returns [Success.data] or `null`. */
    fun getOrNull(): T? = (this as? Success)?.data

    /** Returns [Success.data] or [fallback] for any non-success state. */
    fun getOrDefault(fallback: @UnsafeVariance T): T =
        if (this is Success) data else fallback
}

/** Maps [Success.data] through [transform]; passes [Error] / [Loading] through unchanged. */
inline fun <T, R> Resource<T>.map(transform: (T) -> R): Resource<R> = when (this) {
    is Resource.Success -> Resource.Success(transform(data))
    is Resource.Error -> this
    Resource.Loading -> Resource.Loading
}

/** Executes [block] only when this is [Success]. */
inline fun <T> Resource<T>.onSuccess(block: (T) -> Unit): Resource<T> {
    if (this is Resource.Success) block(data)
    return this
}

/** Executes [block] only when this is [Error]. */
inline fun <T> Resource<T>.onError(block: (Throwable) -> Unit): Resource<T> {
    if (this is Resource.Error) block(throwable)
    return this
}
