package edu.ucsd.sysnet.cluetoothscanner.api.platform

/**
 * AGENTS.md (ab19387): every fallible `:platform-api` call returns a typed result and never throws; the caller must
 * handle [Err] to compile. Exceptions exist only inside `:platform`'s runners, which turn them into a [PlatformError].
 * The same shape as `:core-api`'s `CoreResult`.
 */
sealed interface PlatformResult<out T> {
    data class Ok<out T>(val value: T) : PlatformResult<T>
    data class Err(val error: PlatformError) : PlatformResult<Nothing>
}

/** Why a platform call failed. [message] is the one-line text the UI may show. */
sealed interface PlatformError {
    val message: String

    /** A recording needs these Android permissions first (§1.2.3); nothing was started. */
    data class MissingGrants(val permissions: List<String>) : PlatformError {
        override val message get() = "Nearby devices and precise location are needed to record (${permissions.joinToString()})"
    }

    /** Android did not allow the recording's foreground service; no session was created. */
    data object ServiceRefused : PlatformError {
        override val message get() = "Android did not allow the recording service to start"
    }

    /** Another recording transition is in progress; ask again when it finished. */
    data object Busy : PlatformError {
        override val message get() = "A recording transition is in progress"
    }

    /** There is no recording to pause, resume or stop. */
    data object NotRecording : PlatformError {
        override val message get() = "Not recording"
    }

    /** The core refused or failed the call (for example storage still full on resume, R-10). */
    data class Core(override val message: String) : PlatformError

    /** O-2 step 3: the user id is not 1–9 lowercase letters and digits ([Settings.isValidUserId]); nothing was saved. */
    data class InvalidUserId(val userId: String) : PlatformError {
        override val message get() = "Lowercase letters and numbers only, max 9 characters"
    }

    /** A storage read or write failed (settings, the export copy). */
    data class Io(override val message: String) : PlatformError

    /** Android could not do it on this device (no activity for the intent, a refused system call). */
    data class Unavailable(override val message: String) : PlatformError
}
