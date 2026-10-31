package edu.ucsd.sysnet.cluetoothscanner.platform

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The handler every coroutine `:platform` launches carries (AGENTS.md: every top-level scope has a handler that moves
 * its state to Failed). An exception that escapes a runner (never expected: every effect already goes through the
 * runner's wrapper) is logged and handed to [onFailure], which records it in the owner's state; it never reaches the
 * main thread's uncaught handler.
 */
internal fun failureHandler(owner: String, onFailure: (Throwable) -> Unit = {}): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, failure ->
        // android.util.Log is a stub that throws in JVM tests; logging must never turn a failure into a crash.
        runCatching { Log.e(owner, "a $owner coroutine failed", failure) }
        runCatching { onFailure(failure) }
    }

/** A failure as the one-line text the UI shows. */
internal fun Throwable.describe(): String = message ?: this::class.java.simpleName
