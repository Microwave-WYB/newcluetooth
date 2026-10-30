package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner

// D-13.5: why a GATT discovery failed, in plain English, with the code kept as small secondary text for bug reports.

/** What the device sheet knows about the device when a discovery fails (for the "no target" case). */
internal data class GattDeviceFacts(val connectable: Boolean?, val lastSeenMs: Long?, val nowMs: Long) {
    /** Seen within [RECENT_MS]; unknown counts as recent, so the general reason shows. */
    val seenRecently: Boolean get() = lastSeenMs?.let { nowMs - it <= RECENT_MS } ?: true

    companion object {
        /** "Scanned in the last 20 s": the in-range window. */
        const val RECENT_MS = 20_000L
        val Unknown = GattDeviceFacts(connectable = null, lastSeenMs = null, nowMs = 0)
    }
}

/** Why a discovery failed, each case carrying exactly the code it stands for (none for the runner's own outcomes). */
internal sealed interface GattProblem {
    /** The code shown after the reason, e.g. "(code -5)"; null when there is none. */
    val code: Int?

    // the runner's codes (GattRunner)
    /** -4: Bluetooth is off or BLUETOOTH_CONNECT is missing. */
    data object Unavailable : GattProblem { override val code = GattRunner.GATT_UNAVAILABLE }
    /** -5: the core has no connectable address for the device. */
    data object NoTarget : GattProblem { override val code = GattRunner.GATT_NO_TARGET }
    /** -5, and the device advertises as not connectable. */
    data object NotConnectable : GattProblem { override val code = GattRunner.GATT_NO_TARGET }
    /** -5, and the device has not been scanned in the last 20 s. */
    data object NotSeenRecently : GattProblem { override val code = GattRunner.GATT_NO_TARGET }
    /** -6: the engine itself failed (a link call threw mid-discovery). */
    data object EngineFailed : GattProblem { override val code = GattRunner.GATT_INTERNAL_ERROR }
    /** -2: the app's process died while the discovery ran (recorded on the next start). */
    data object ProcessDied : GattProblem { override val code = GattRunner.GATT_PROCESS_DEATH }
    /** -3: a read never answered, and Android allows only one outstanding request. */
    data object ReadStalled : GattProblem { override val code = GattRunner.GATT_READ_QUEUE_STALLED }

    // Android's GATT statuses
    data object GattError : GattProblem { override val code = 133 }
    data object ConnectionTimeout : GattProblem { override val code = 8 }
    data object TerminatedByPeer : GattProblem { override val code = 19 }
    data object TerminatedLocally : GattProblem { override val code = 22 }
    data object FailedToEstablish : GattProblem { override val code = 62 }
    data object InsufficientAuthentication : GattProblem { override val code = 5 }
    data object InsufficientEncryption : GattProblem { override val code = 15 }
    /** 257 GATT_FAILURE: the stack refused the call itself. */
    data object StackRefused : GattProblem { override val code = 257 }
    /** Any other status. */
    data class Unknown(override val code: Int) : GattProblem

    // what the runner classified itself (§2.7)
    data object TimedOut : GattProblem { override val code: Int? = null }
    data object DisconnectedUnexpectedly : GattProblem { override val code: Int? = null }
    data object ConnectionFailed : GattProblem { override val code: Int? = null }
    data object Cancelled : GattProblem { override val code: Int? = null }
    /** A failed step without a reason (the runner itself failed). */
    data object Unreported : GattProblem { override val code: Int? = null }
}

/** The problem behind a failed step's [failure]; for "no target", the device's facts say why when they can. */
internal fun gattProblem(failure: GattFailure?, device: GattDeviceFacts): GattProblem = when (failure) {
    null -> GattProblem.Unreported
    GattFailure.TimedOut -> GattProblem.TimedOut
    GattFailure.DisconnectedUnexpectedly -> GattProblem.DisconnectedUnexpectedly
    GattFailure.ConnectionFailed -> GattProblem.ConnectionFailed
    GattFailure.Cancelled -> GattProblem.Cancelled
    is GattFailure.Other -> when (failure.status) {
        GattRunner.GATT_UNAVAILABLE -> GattProblem.Unavailable
        GattRunner.GATT_INTERNAL_ERROR -> GattProblem.EngineFailed
        GattRunner.GATT_PROCESS_DEATH -> GattProblem.ProcessDied
        GattRunner.GATT_READ_QUEUE_STALLED -> GattProblem.ReadStalled
        GattRunner.GATT_NO_TARGET -> when {
            device.connectable == false -> GattProblem.NotConnectable
            !device.seenRecently -> GattProblem.NotSeenRecently
            else -> GattProblem.NoTarget
        }
        133 -> GattProblem.GattError
        8 -> GattProblem.ConnectionTimeout
        19 -> GattProblem.TerminatedByPeer
        22 -> GattProblem.TerminatedLocally
        62 -> GattProblem.FailedToEstablish
        5 -> GattProblem.InsufficientAuthentication
        15 -> GattProblem.InsufficientEncryption
        257 -> GattProblem.StackRefused
        else -> GattProblem.Unknown(failure.status)
    }
}

/** D-13.5: the one-line reason shown under the failed step. */
internal fun GattProblem.reason(): String = when (this) {
    GattProblem.Unavailable -> "Bluetooth is off or permission is missing"
    GattProblem.NoTarget -> "This device hasn't been seen with a connectable address yet. Stay near it and try again"
    GattProblem.NotConnectable -> "This device doesn't accept connections"
    GattProblem.NotSeenRecently -> "This device hasn't been seen in the last 20 s. Move closer and try again"
    GattProblem.EngineFailed -> "The Bluetooth stack failed during discovery"
    GattProblem.ProcessDied -> "The app was closed while the discovery ran"
    GattProblem.ReadStalled -> "The device stopped answering reads"
    GattProblem.GattError -> "The connection failed with a generic Bluetooth error. Try again"
    GattProblem.ConnectionTimeout -> "The device stopped responding"
    GattProblem.TerminatedByPeer -> "The device closed the connection"
    GattProblem.TerminatedLocally -> "This phone closed the connection"
    GattProblem.FailedToEstablish -> "Couldn't establish a connection. The device may be out of range"
    GattProblem.InsufficientAuthentication -> "The device requires pairing"
    GattProblem.InsufficientEncryption -> "The device requires an encrypted (paired) connection"
    GattProblem.StackRefused -> "Android's Bluetooth stack refused the request"
    is GattProblem.Unknown -> "Unexpected Bluetooth error"
    GattProblem.TimedOut -> "The device didn't respond in time"
    GattProblem.DisconnectedUnexpectedly -> "The device disconnected unexpectedly"
    GattProblem.ConnectionFailed -> "Couldn't connect to the device"
    GattProblem.Cancelled -> "Cancelled"
    GattProblem.Unreported -> "Failed"
}

/** The secondary text after the reason: "(code -5)", or null. */
internal fun GattProblem.codeText(): String? = code?.let { "(code $it)" }
