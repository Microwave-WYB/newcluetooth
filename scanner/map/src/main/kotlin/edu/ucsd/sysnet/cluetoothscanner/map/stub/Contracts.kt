package edu.ucsd.sysnet.cluetoothscanner.map.stub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * TEMPORARY. Until :core-api (A0-api), :ui-contract (U0) and :platform-api (B0) merge, these are verbatim copies of
 * the frozen types in docs/v2-api.md §5 that :map uses, and only those. When the foundations land, this file is
 * deleted and the imports switch to edu.ucsd.sysnet.cluetoothscanner.api.core / .api.platform / .ui.contract.
 * Nothing here is a design decision of lane E; do not extend it beyond the contract.
 */

// ---- :core-api (§5.1) ----

typealias DeviceKey = Long

sealed interface SessionScope {
    data object All : SessionScope
    data class Sessions(val ids: List<String>) : SessionScope
}
data class SessionFilter(val startedFromMs: Long? = null, val startedToMs: Long? = null)
data class Scope(val sessions: SessionScope, val filter: SessionFilter? = null) {
    companion object {
        val All = Scope(SessionScope.All)
        fun session(id: String) = Scope(SessionScope.Sessions(listOf(id)))
    }
}
data class GeoPoint(val lon: Double, val lat: Double)
data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double)
class DeviceKeys(val keys: LongArray, val seq: Long)

enum class Identification { Any, Identified, Unidentified }
data class DeviceFilter(
    val area: Bbox? = null, val identification: Identification = Identification.Any,
    val categories: Set<String> = emptySet(),
    val readable: Boolean = false, val trackers: Boolean = false, val vulnerable: Boolean = false,
    val inRangeOnly: Boolean = false, val page: String? = null, val query: String? = null,
)
enum class PageKind { Device, Platform, Identifier, Vulnerability }
data class PageRef(val id: String, val kind: PageKind, val title: String)

data class SessionView(val scope: Scope, val live: Boolean, val sessionIds: List<String>, val deviceCount: Int,
                       val frame: Bbox?, val route: List<GeoPoint>, val routesFaint: Boolean, val seq: Long)
data class DeviceRow(val key: DeviceKey, val addr: String, val name: String?, val page: PageRef?,
                     val categories: List<String>, val traits: List<String>, val readable: Boolean,
                     val vulnerable: Boolean, val icon: String?, val rssi: Int?, val inRange: Boolean, val spark: ByteArray?,
                     val best: GeoPoint?, val lastSeenMs: Long)
data class Sighting(val at: GeoPoint, val rssi: Int, val seenMs: Long)
enum class MapMode { Points, Cells }
data class MapData(val mode: MapMode, val count: Int, val geojson: String, val seq: Long)

/** §2.6, 1:1. RecordingState and ProductCard are left out: :map never reads them. */
data class LiveDelta(val sessionId: String, val seq: Long, val atMs: Long, val changed: List<DeviceLive>,
                     val appended: List<DeviceRow>, val routeAppended: List<GeoPoint>)
data class DeviceLive(val key: DeviceKey, val rssi: Int?, val inRange: Boolean, val spark: ByteArray,
                      val name: String?, val page: PageRef?, val best: GeoPoint?)

/** The subset of CoreQueries (§5.1) that :map calls; same signatures. */
interface CoreQueries {
    val live: Flow<LiveDelta>
    suspend fun sessionView(scope: Scope): SessionView
    suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double): String
    suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow>
    suspend fun deviceSightings(scope: Scope, key: DeviceKey): List<Sighting>
    suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData
}

// ---- :platform-api (§5.2) ----

data class LocationStatus(val state: State, val warn: Boolean, val ageMin: Int? = null, val action: Action? = null) {
    enum class State { Ok, Denied, Off, Waiting, Stale }
    enum class Action { Allow, TurnOn }
}
data class MyLocation(val at: GeoPoint, val accuracyM: Double, val ageMs: Long)
interface LocationStatusSource { val status: StateFlow<LocationStatus>; val myLocation: StateFlow<MyLocation?> }

// ---- :ui-contract (§5.3) ----

data class CameraSnapshot(val center: GeoPoint, val zoom: Double, val bearing: Double = 0.0)
data class CoverInsets(val top: Int, val bottom: Int, val left: Int, val right: Int)

interface MapRequests {
    fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean)
    fun frame(bbox: Bbox)
    fun focusDevice(key: DeviceKey, minZoom: Double = 18.2)
    fun setSelection(key: DeviceKey?)
    fun camera(): CameraSnapshot; fun restore(camera: CameraSnapshot)
    fun setCover(insets: CoverInsets)
}
interface MapEvents {
    fun onUserGestureStart()
    fun onCameraIdle(byUser: Boolean, viewport: Bbox, zoom: Double)
    fun onDeviceTap(key: DeviceKey); fun onEmptyTap()
}
interface MapCacheInfo { suspend fun sizeBytes(): Long }
