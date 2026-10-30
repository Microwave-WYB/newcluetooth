package edu.ucsd.sysnet.cluetoothscanner.sheets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** A fallible call's result as a value: what it returned, or why it failed. */
internal sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val message: String) : Outcome<Nothing>
}

/**
 * The one wrapper every core and GATT call of :sheets goes through: an exception becomes [Outcome.Failed] (a
 * cancellation still cancels), and the runner turns the outcome into a typed event for its reducer. Nothing a core
 * or GATT call throws reaches a coroutine scope.
 */
internal suspend fun <T> attempt(call: suspend () -> T): Outcome<T> = try {
    Outcome.Ok(call())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Outcome.Failed(failureMessage(e))
}

/** A flow's values as [Outcome.Ok], and its failure (if any) as a last [Outcome.Failed]; it never throws. */
internal fun <T> Flow<T>.outcomes(): Flow<Outcome<T>> =
    map<T, Outcome<T>> { Outcome.Ok(it) }.catch { e -> if (e is Exception && e !is CancellationException) emit(Outcome.Failed(failureMessage(e))) else throw e }

internal fun failureMessage(e: Throwable): String = e.message ?: e.toString()
