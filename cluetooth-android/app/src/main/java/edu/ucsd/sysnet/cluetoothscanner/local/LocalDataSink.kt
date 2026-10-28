package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput

/**
 * What the scan pipeline feeds the local stores. Calls come from scan and location
 * callbacks, so they must return immediately and never throw.
 */
interface LocalDataSink {
    /** One advertisement heard; [rssi] null when the platform reported none. */
    fun onAdvertisement(addr: String, rssi: Int?, raw: ByteArray, timeMs: Long)

    fun onLocation(fix: CoreLocationInput)

    fun onLocationCleared()

    /** A recording (core scan session) began; history and the route are kept from now on. */
    fun onRecordingStarted(recordingId: String, startedAtMs: Long)

    fun onRecordingFinished(recordingId: String, endedAtMs: Long)

    object None : LocalDataSink {
        override fun onAdvertisement(addr: String, rssi: Int?, raw: ByteArray, timeMs: Long) = Unit
        override fun onLocation(fix: CoreLocationInput) = Unit
        override fun onLocationCleared() = Unit
        override fun onRecordingStarted(recordingId: String, startedAtMs: Long) = Unit
        override fun onRecordingFinished(recordingId: String, endedAtMs: Long) = Unit
    }
}
