// AGENTS.md: fallible :core-api calls return a typed result instead of throwing. New calls use it now; the existing
// ones move to it in the planned migration.
package edu.ucsd.sysnet.cluetoothscanner.api.core

sealed interface CoreResult<out T> {
    data class Ok<out T>(val value: T) : CoreResult<T>
    data class Err(val error: CoreException) : CoreResult<Nothing>
}
