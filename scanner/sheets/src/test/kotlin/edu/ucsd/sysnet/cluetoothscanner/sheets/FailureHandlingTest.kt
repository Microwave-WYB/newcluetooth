package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeDemo
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattDevice
import edu.ucsd.sysnet.cluetoothscanner.platform.fake.FakeGattRunner
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CameraSnapshot
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapRequests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #72 review 1 and 4: core, GATT and runner failures come back as values and states, never as throws. */
@OptIn(ExperimentalCoroutinesApi::class)
class FailureHandlingTest {
    private class FlakyMap : MapRequests {
        var failShow = false
        override fun show(scope: Scope, filter: DeviceFilter, routesFaint: Boolean) {
            if (failShow) throw IllegalStateException("map gone")
        }
        override fun frame(bbox: Bbox) = Unit
        override fun focusDevice(key: DeviceKey, minZoom: Double) = Unit
        override fun setSelection(key: DeviceKey?) = Unit
        override fun camera() = CameraSnapshot(GeoPoint(-117.23, 32.88), 15.0)
        override fun restore(camera: CameraSnapshot) = Unit
        override fun setCover(insets: CoverInsets) = Unit
    }

    /** :core-fake's history session, with every read able to fail on demand. */
    private class FlakyCore(val core: FakeCore) : CoreQueries by core {
        var failOrder = 0
        var failReads = false
        override suspend fun deviceOrder(scope: Scope): DeviceKeys {
            if (failOrder-- > 0) throw IllegalStateException("core closed")
            return core.deviceOrder(scope)
        }
        override suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow> =
            if (failReads) throw IllegalStateException("read failed") else core.deviceRows(scope, keys)
        override suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail? =
            if (failReads) throw IllegalStateException("read failed") else core.device(scope, key)
        override suspend fun deviceGatt(key: DeviceKey): GattView? =
            if (failReads) throw IllegalStateException("read failed") else core.deviceGatt(key)
    }

    private fun TestScope.sheets(core: FlakyCore, map: MapRequests?, gatt: GattRunner = FakeGattRunner({ FakeGattDevice(null, true) })): SheetsState {
        return SheetsState(
            core.core.initialScope!!, live = false, routesFaint = false, core = core, gattRunner = gatt, map = map,
            coroutines = this, nowMs = { FakeCore.DEMO_NOW_MS }, metrics = SheetMetrics(reducedMotion = true),
        ).also { it.stack.setScreen(915f, 1f, 0f) }
    }

    private fun TestScope.fakeCore() = FakeCore(backgroundScope, clock = { FakeCore.DEMO_NOW_MS }, demo = FakeDemo.History, simulate = false)

    @Test
    fun `D-13_1 a failed first sort shows Failed, and Try again reads it again`() = runTest {
        val core = FlakyCore(fakeCore()).apply { failOrder = 1 }
        val s = sheets(core, FlakyMap())
        s.open()
        assertEquals(DiscoverPhase.Failed("core closed"), s.discover.phase)
        s.retry()
        advanceUntilIdle()
        assertTrue(s.discover.phase is DiscoverPhase.Ready)
    }

    @Test
    fun `a failure that escapes a runner moves Discover to Failed instead of crashing, and Try again recovers`() = runTest {
        val map = FlakyMap()
        val s = sheets(FlakyCore(fakeCore()), map)
        s.open()
        map.failShow = true
        s.selectSegment(Segment.ThisArea) // its map follow-up throws
        advanceUntilIdle()
        assertEquals(DiscoverPhase.Failed("map gone"), s.discover.phase)
        map.failShow = false
        s.retry()
        advanceUntilIdle()
        assertTrue(s.discover.phase is DiscoverPhase.Ready)
    }

    @Test
    fun `failed reads come back as Failed outcomes, and failed row pages leave placeholders`() = runTest {
        val core = FlakyCore(fakeCore())
        val s = sheets(core, null)
        s.open()
        val key = s.discover.rows.first()
        core.failReads = true
        assertEquals(Outcome.Failed("read failed"), s.load(SheetQuery.Device(key)))
        assertEquals(Loaded.Failed("read failed"), DeviceContent().withDetail(s.load(SheetQuery.Device(key))).detail)
        s.rows.ensure(listOf(key))
        advanceUntilIdle()
        assertNull(s.rows[key])
        core.failReads = false
        s.rows.ensure(listOf(key)) // asked again: loads now
        advanceUntilIdle()
        assertEquals(key, s.rows[key]?.key)
    }

    @Test
    fun `D-13 a GATT runner that throws fails the running step instead of crashing`() = runTest {
        val throwing = object : GattRunner {
            override fun discover(key: DeviceKey): Flow<GattView> = flow { throw IllegalStateException("adapter off") }
        }
        val g = GattController(7, this, throwing) { 0 }
        g.onDiscover()
        g.onAuthorized()
        advanceUntilIdle()
        val failed = g.state as? GattUi.Failed
        assertTrue("${g.state}", failed != null)
        g.onTryAgain()
        assertEquals(GattUi.Confirm, g.state)
    }

    @Test
    fun `S-2_6 a row write bumps the version, reads see the table without copying it`() = runTest {
        val s = sheets(FlakyCore(fakeCore()), null)
        s.open()
        val keys = s.discover.rows.take(3)
        val before = s.rows.version
        s.rows.ensure(keys)
        advanceUntilIdle()
        assertEquals(before + 1, s.rows.version) // one page, one write
        assertEquals(keys, keys.mapNotNull { s.rows[it]?.key })
    }
}
