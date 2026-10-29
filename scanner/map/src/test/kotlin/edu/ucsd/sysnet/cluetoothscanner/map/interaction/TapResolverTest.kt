package edu.ucsd.sysnet.cluetoothscanner.map.interaction

import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** M-7 and M-4 tap outcomes. */
class TapResolverTest {
    private val at = GeoPoint(1.0, 2.0)

    @Test fun `a point opens its device`() =
        assertEquals(TapAction.OpenDevice(0xAABBCC001122), TapResolver.resolve(Hit("points", "AA:BB:CC:00:11:22", at)))

    @Test fun `a cluster expands, a cell zooms, a sighting does nothing, nothing is empty`() {
        val cluster = Hit("clusters", null, at)
        assertEquals(TapAction.ExpandCluster(cluster), TapResolver.resolve(cluster))
        assertEquals(TapAction.ZoomCell(at), TapResolver.resolve(Hit("cells", null, at)))
        assertEquals(TapAction.Ignore, TapResolver.resolve(Hit("sightings", null, at)))
        assertEquals(TapAction.Empty, TapResolver.resolve(null))
    }

    @Test fun `hit test order is top-down as the style draws them`() =
        assertEquals(listOf("sightings", "points", "cells", "clusters"), TapResolver.LAYERS_TOP_DOWN)

    @Test fun `M-4 cluster zoom is the expansion zoom + 0_2, a cell zooms by 2`() {
        assertEquals(14.2, TapResolver.clusterZoom(14), 1e-9)
        assertEquals(13.5, TapResolver.cellZoom(11.5), 1e-9)
    }

    @Test fun `device keys and addresses round-trip`() {
        assertEquals("72:CD:4C:4B:4B:6F", DeviceAddr.addrOf(0x72CD4C4B4B6F))
        assertEquals(0x72CD4C4B4B6F, DeviceAddr.keyOf("72:CD:4C:4B:4B:6F"))
        assertEquals(0xFFFFFFFFFFFF, DeviceAddr.keyOf(DeviceAddr.addrOf(0xFFFFFFFFFFFF)))
        assertEquals(null, DeviceAddr.keyOf("nope"))
    }
}
