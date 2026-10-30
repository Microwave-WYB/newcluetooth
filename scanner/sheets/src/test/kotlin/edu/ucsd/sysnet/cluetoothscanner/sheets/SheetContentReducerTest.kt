package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.AdStructure
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pushed sheets' content reducers. Async results enter only as events, so any arrival order ends in the same
 * content; the owner's blank live device sheet (the chart's write putting the detail back to Pending) can't happen.
 */
class SheetContentReducerTest {
    private val detail = DeviceDetail(
        key = 7, addr = "AA:BB:CC:DD:EE:FF", name = "AirPods Pro", page = null, categories = emptyList(), traits = emptyList(),
        readable = false, vulnerable = false, vulnerabilities = emptyList(), wikiUrl = null, icon = null, rssi = -58,
        inRange = true, live = true, connectable = true, format = AdvFormat.Legacy, companyId = null, companyName = null,
        serviceUuid = null, txPower = null, structures = listOf(AdStructure(2, 1, byteArrayOf(6))), tail = null, decoded = null, lastSeenMs = 0,
    )
    private val chart = LiveChart(7, 1_000, ByteArray(60) { 58 }, -58)
    private val delta = LiveChart(7, 2_000, ByteArray(60) { 60 }, -60)
    private val lastGatt = GattView("g1", 7, 0, ok = true, steps = listOf(GattStep(GattStepKind.Connecting, GattStepState.Done, 800, null, null, null, null)), services = emptyList())

    /** One arrival: a device-content event, or the stored GATT result into the GATT tab's own state machine. */
    private sealed interface Arrival {
        data class Content(val event: DeviceEvent) : Arrival
        data object LastGatt : Arrival
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { x -> permutations(items - x).map { listOf(x) + it } }

    @Test
    fun `every arrival order of detail, chart and the stored GATT result ends in the same sheet`() {
        val arrivals = listOf(
            Arrival.Content(DeviceEvent.DetailLoaded(Outcome.Ok(detail))),
            Arrival.Content(DeviceEvent.ChartLoaded(Outcome.Ok(chart))),
            Arrival.LastGatt,
        )
        val ends = permutations(arrivals).map { order ->
            var content = DeviceContent()
            var gatt = GattState()
            for (a in order) when (a) {
                is Arrival.Content -> content = reduceDevice(content, a.event)
                Arrival.LastGatt -> gatt = reduceGatt(gatt, GattEvent.LastLoaded(lastGatt)).state
            }
            content to gatt
        }
        assertEquals(6, ends.size)
        for ((content, gatt) in ends) {
            assertEquals(Loaded.Found(detail), content.detail)
            assertEquals(ChartState.Live(chart), content.chart)
            assertEquals(GattUi.Done(lastGatt), gatt.ui)
        }
    }

    @Test
    fun `the owner's case - a live chart landing after the detail keeps the detail`() {
        val opened = reduceDevice(DeviceContent(), DeviceEvent.DetailLoaded(Outcome.Ok(detail)))
        val charted = reduceDevice(opened, DeviceEvent.ChartLoaded(Outcome.Ok(chart)))
        val updated = reduceDevice(charted, DeviceEvent.ChartDelta(delta))
        assertEquals(Loaded.Found(detail), updated.detail)
        assertEquals(ChartState.Live(delta), updated.chart)
        // a tab switch and a failed chart read change only their own part
        val tab = reduceDevice(updated, DeviceEvent.TabSelected(DeviceTab.Gatt))
        assertEquals(updated.copy(tab = DeviceTab.Gatt), tab)
        assertEquals(tab, reduceDevice(tab, DeviceEvent.ChartLoaded(Outcome.Failed("busy"))))
    }

    @Test
    fun `the SheetMachine reduces the current state on every dispatch`() {
        val m = SheetMachine(DeviceContent(), ::reduceDevice)
        m.dispatch(DeviceEvent.DetailLoaded(Outcome.Ok(detail)))
        m.dispatch(DeviceEvent.ChartLoaded(Outcome.Ok(chart)))
        assertEquals(DeviceContent(Loaded.Found(detail), ChartState.Live(chart)), m.state)
    }

    private val product = ProductDetail(null, "Apple AirPods", null, listOf("audio"), emptyList(), false, emptyList(), null, null, null, 3, null, 2)

    @Test
    fun `every arrival order of a product's detail and keys ends in the same sheet`() {
        val events = listOf(ProductEvent.DetailLoaded(Outcome.Ok(product), "Audio"), ProductEvent.KeysLoaded(longArrayOf(3, 1)))
        for (order in permutations(events)) {
            val end = order.fold(ProductContent(), ::reduceProduct)
            assertEquals(Loaded.Found(product), end.detail)
            assertEquals("Audio", end.title)
            assertArrayEquals(longArrayOf(3, 1), end.keys)
        }
        // a later keys answer (a live append) replaces only the keys
        val first = events.fold(ProductContent(), ::reduceProduct)
        assertEquals(first.detail, reduceProduct(first, ProductEvent.KeysLoaded(longArrayOf(3, 1, 9))).detail)
    }

    @Test
    fun `the Filter rows' first counts fix the order and later ones update in place`() {
        val counts = listOf(CategoryCount("audio", "Audio", 5), CategoryCount("lighting", "Lighting", 9))
        val shown = reduceFilterRows(FilterRows.Pending, FilterCounts(counts, setOf("audio"), mapOf("audio" to "Audio device")))
        val first = (shown as FilterRows.Shown).content
        assertEquals("Audio", first.labels["audio"]) // the count's own label
        val later = reduceFilterRows(shown, FilterCounts(counts.reversed().map { it.copy(deviceCount = 1) }, emptySet(), emptyMap()))
        assertEquals(first.rowOrder, (later as FilterRows.Shown).content.rowOrder)
    }

    @Test
    fun `the category cards - a failed refresh keeps them, a failed first read says so`() {
        val groups = listOf(CategoryGroup("audio", "Audio", emptyList()))
        val loaded = reduceCategories(Loaded.Pending, CategoriesEvent.Loaded(Outcome.Ok(groups)))
        assertEquals(Loaded.Found(groups), loaded)
        assertSame(loaded, reduceCategories(loaded, CategoriesEvent.Refreshed(Outcome.Failed("busy"))))
        assertEquals(Loaded.Found(emptyList<CategoryGroup>()), reduceCategories(loaded, CategoriesEvent.Refreshed(Outcome.Ok(emptyList()))))
        assertTrue(reduceCategories(Loaded.Pending, CategoriesEvent.Loaded(Outcome.Failed("closed"))) is Loaded.Failed)
    }
}
