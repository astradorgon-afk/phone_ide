package dev.mobileforge.core.common

/**
 * The result type used across subsystem boundaries.
 *
 * Exists because the brief forbids silently swallowed errors: a failure must carry a category,
 * a human-readable explanation, optional technical detail, and a recovery suggestion. A bare
 * [Throwable] carries none of that, and "Command failed" is exactly the error message this
 * project is trying not to produce.
 */
sealed interface AppResult<out T> {

    data class Success<T>(val value: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(value)
        is AppResult.Failure -> this
    }

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)

fun AppError.asFailure(): AppResult<Nothing> = AppResult.Failure(this)
