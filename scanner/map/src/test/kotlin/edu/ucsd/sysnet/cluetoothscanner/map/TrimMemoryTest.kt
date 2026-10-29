package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatusSource
import edu.ucsd.sysnet.cluetoothscanner.api.platform.MyLocation
import edu.ucsd.sysnet.cluetoothscanner.map.camera.MapGeometry
import edu.ucsd.sysnet.cluetoothscanner.map.camera.Viewport
import edu.ucsd.sysnet.cluetoothscanner.map.data.GeoJson
import edu.ucsd.sysnet.cluetoothscanner.map.interaction.Hit
import edu.ucsd.sysnet.cluetoothscanner.map.style.SelectionProp
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §1.2.5 through the runner: after Trim no copy of the point GeoJSON is kept, on the map or in the runner. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrimMemoryTest {
    private val center = GeoPoint(-117.234, 32.88)
    /** Large enough that keeping it would show. */
    private val points = (0 until 500).map { GeoPoint(center.lon + it * 1e-5, center.lat) }
    private val pointsJson = """{"type":"FeatureCollection","features":[""" +
        points.joinToString(",") { """{"type":"Feature","properties":{"addr":"AA:BB:CC:00:00:01"},"geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]}}""" } + "]}"

    private inner class Queries : UnusedCoreQueries() {
        override val live = MutableSharedFlow<LiveDelta>()
        var pointsCalls = 0
        override suspend fun sessionView(scope: Scope) =
            SessionView(scope, false, listOf("s"), points.size, MapGeometry.bboxOf(points), emptyList(), false, 0)
        override suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double) = GeoJson.EMPTY
        override suspend fun deviceRows(scope: Scope, keys: LongArray) = emptyList<DeviceRow>()
        override suspend fun deviceSightings(scope: Scope, key: DeviceKey) = points.map { Sighting(it, -60, 0) }
        override suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData {
            pointsCalls++
            return MapData(MapMode.Points, points.size, pointsJson, 0)
        }
    }

    private class Location : LocationStatusSource {
        override val status = MutableStateFlow(LocationStatus.Ok)
        override val myLocation = MutableStateFlow<MyLocation?>(null)
    }

    private class Surface : MapSurface {
        lateinit var listener: MapSurfaceListener
        override val viewport = Viewport(412.0, 915.0)
        override var camera = CameraSnapshot(GeoPoint(-117.234, 32.88), 15.0)
        override val visibleBbox get() = MapGeometry.visibleBbox(camera, viewport)
        val sources = HashMap<String, String>()
        override fun setGeoJson(source: String, geojson: String) { sources[source] = geojson }
        override fun applySelection(props: List<SelectionProp>) = Unit
        override fun setRouteOpacity(opacity: Double) = Unit
        override fun move(camera: CameraSnapshot, durationMs: Int) { this.camera = camera; listener.onIdle() }
        override fun clusterExpansionZoom(hit: Hit) = 14
    }

    @Test fun `after trim neither the map nor the runner holds the point data, and a refill brings it back`() = runTest {
        val q = Queries()
        val surface = Surface()
        val state = CluetoothMapState(q, Location(), Design.style, backgroundScope)
        surface.listener = state
        state.attach(surface)
        state.show(Scope.session("s"), DeviceFilter(), routesFaint = false)
        state.setSelection(1L)
        runCurrent()
        assertEquals(pointsJson, surface.sources["devices"])
        assertTrue("the runner holds the points before the trim", state.retainedJson().values.any { it >= pointsJson.length })

        state.trimMemory()
        runCurrent()
        for (s in listOf("devices", "density", "cells", "sightings")) assertEquals(s, GeoJson.EMPTY, surface.sources[s])
        val retained = state.retainedJson()
        assertTrue("runner keeps $retained", retained.values.all { it <= GeoJson.EMPTY.length })

        val calls = q.pointsCalls
        state.refillAfterTrim()
        runCurrent()
        assertEquals(calls + 1, q.pointsCalls)
        assertEquals(pointsJson, surface.sources["devices"])
        assertTrue(surface.sources.getValue("sightings").length > GeoJson.EMPTY.length)
    }
}
