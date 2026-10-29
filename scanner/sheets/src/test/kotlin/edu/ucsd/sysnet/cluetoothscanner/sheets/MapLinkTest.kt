package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The :ui-contract map seam: S-8 camera restore through MapRequests; P-6, M-7, S-4, T-2 through MapEvents. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapLinkTest {
    private class FakeMap : MapRequests {
        var current = CameraSnapshot(GeoPoint(-117.2, 32.9), 15.0)
        val restored = mutableListOf<CameraSnapshot>()
        override fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean) = Unit
        override fun frame(bbox: Bbox) = Unit
        override fun focusDevice(key: DeviceKey, minZoom: Double) = Unit
        override fun setSelection(key: DeviceKey?) = Unit
        override fun camera() = current
        override fun restore(camera: CameraSnapshot) {
            restored += camera
            current = camera
        }
        override fun setCover(insets: CoverInsets) = Unit
    }

    private fun TestScope.stack(map: FakeMap) = SheetStackController(
        scope = this,
        animator = { _, to, _, onValue -> onValue(to) },
        hooks = mapStackHooks(map),
        rootHeight = SheetHeight.Half,
    ).also { it.setScreen(915f, 1f, 0f) }

    @Test
    fun `S-8 the map gets back the camera saved under each level`() = runTest {
        val map = FakeMap()
        val s = stack(map)
        val atRoot = map.current
        s.push(SheetKind.Product("p"))
        map.current = CameraSnapshot(GeoPoint(-117.0, 32.0), 12.0) // the product framed its devices
        val atProduct = map.current
        s.openDevice(9)
        map.current = CameraSnapshot(GeoPoint(-117.0, 32.0), 18.2)
        s.pop(PopReason.Close)
        s.pop(PopReason.EmptyMap)
        advanceUntilIdle()
        assertEquals(listOf(atProduct, atRoot), map.restored)
    }

    @Test
    fun `P-6 M-7 S-4 map events drive the stack`() = runTest {
        val s = stack(FakeMap())
        val events = SheetMapEvents(s)
        events.onDeviceTap(1)
        events.onDeviceTap(2) // replaces the device sheet on top
        assertEquals(listOf(SheetKind.Discover, SheetKind.Device(2)), s.entries.map { it.kind })
        events.onUserGestureStart()
        assertEquals(SheetHeight.Collapsed, s.top.height)
        events.onEmptyTap()
        assertEquals(1, s.entries.size)
        events.onEmptyTap() // only the root: nothing to pop
        assertEquals(1, s.entries.size)
    }

    @Test
    fun `T-2 a user map gesture moves the tablet panel to Partial`() = runTest {
        val s = stack(FakeMap())
        s.setLayout(tablet = true)
        SheetMapEvents(s).onUserGestureStart()
        assertEquals(PanelHeight.Partial, s.top.panel)
    }

    @Test
    fun `D-4 Q-6 camera idle is handed on`() = runTest {
        val seen = mutableListOf<Boolean>()
        val events = SheetMapEvents(stack(FakeMap())) { byUser, _, _ -> seen += byUser }
        events.onCameraIdle(true, Bbox(0.0, 0.0, 1.0, 1.0), 15.0)
        events.onCameraIdle(false, Bbox(0.0, 0.0, 1.0, 1.0), 15.0)
        assertEquals(listOf(true, false), seen)
    }
}
