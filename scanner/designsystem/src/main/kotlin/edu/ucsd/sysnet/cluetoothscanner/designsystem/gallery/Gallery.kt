package edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.BlinkingDot
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.CategoryIcons
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.CategorySection
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Checkbox
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Chip
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ChipRow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.FloatButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Handle
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Icon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ListItem
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ProductCard
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SearchField
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SectionLabel
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Segmented
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SegmentedSize
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SheetFade
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Switch
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tabular
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.TokenShadow

/*
 * The :designsystem part of the component gallery (lane C). It mirrors the prototype's #gallery (app.js
 * renderGallery): the same sections, cells, labels, cell widths and sample data, so each cell can be compared with
 * the prototype's cell of the same name (designsystem/tools/prototype-shots.mjs). The sample data is data.js's.
 * Cell widths copy the gallery's inline styles; they are review layout, not design values.
 */

/** One gallery cell: its section, label, box width (null: as wide as its content) and whether the box is padded. */
class GalleryCell(
    val section: String,
    val label: String,
    val width: Dp? = null,
    val padded: Boolean = true,
    val content: @Composable () -> Unit,
) {
    /** The file name the prototype script writes for the same cell. */
    val slug: String get() = "${slug(section)}__${slug(label)}"
}

private fun slug(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

/** data.js samples (the gallery's normal, stale and no-name devices, and the first products). */
object GallerySamples {
    private fun spark(vararg h: Int?): ByteArray = ByteArray(h.size) { i -> h[i]?.let { (-it).toByte() } ?: 0 }

    val normal = Sample("ihoment_H6008_1A2B", "72:CD:4C:4B:E4:6F", -48, spark(-48, -43, -44, -45, -43, -45, -52, -54, -51, -51, -52, -47, -42, -43, -46, -45, -47, -53, -55, -51, -49, -50, -46, -41, -43, -47, -47, -48, -54, -48))
    val stale = Sample("Hue color lamp", "D8:A6:D2:31:30:FE", -67, spark(-63, -66, -65, -67, -73, -73, -69, -68, -68, -64, -60, -63, -67, -66, -68, -73, -74, -68, -66, -67, null, null, null, null, null, null, null, null, null, null), stale = true)
    val noName = Sample(null, "4D:5D:F7:FA:3A:92", -58, spark(-58, -58, -62, -65, -61, -57, -58, -56, -51, -52, -58, -60, -59, -62, -65, -60, -55, -56, -56, -52, -53, -59, -62, -60, -62, -64, -59, -54, -54, -58))
    val airpods = Sample("AirPods Pro", "40:E7:7D:3F:7A:CD", -52, spark(-46, -48, -50, -49, -52, -58, -58, -54, -54, -54, -48, -45, -48, -51, -50, -53, -59, -58, -53, -52, -52, -48, -45, -49, -53, -52, -54, -59, -58, -52))
    val strip = Sample("Govee_H6199_RGBIC_LED_Strip_Living_Room_Cabinet", "A8:7D:4A:D7:4F:E4", -55, spark(-52, -54, -53, -57, -62, -60, -56, -56, -55, -49, -48, -53, -56, -55, -58, -62, -60, -54, -54, -54, -49, -48, -54, -57, -56, -58, -62, -59, -53, -55))

    class Sample(val name: String?, val mac: String, val rssi: Int, val spark: ByteArray, val stale: Boolean = false)
}

@Composable
private fun Swatch(name: String, color: Color) {
    val c = CluetoothTheme.colors
    Box(Modifier.size(72.dp, 44.dp).background(color, RoundedCornerShape(12.dp)).border(1.dp, c.div, RoundedCornerShape(12.dp)))
}

@Composable
private fun ShadowSwatch(shadow: TokenShadow) {
    val c = CluetoothTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(72.dp, 44.dp).tokenShadow(shadow, shape).background(c.fill1, shape))
}

/** Every :designsystem gallery cell, in the prototype's order. */
@Composable
fun designSystemCells(): List<GalleryCell> {
    val c = CluetoothTheme.colors
    val s = CluetoothTheme.shadows
    val noop = {}
    val colors = listOf(
        "--bg" to c.bg, "--fill1" to c.fill1, "--fill2" to c.fill2, "--panel" to c.panel, "--t1" to c.t1, "--t2" to c.t2,
        "--t3" to c.t3, "--div" to c.div, "--blue" to c.blue, "--chip-on-bg" to c.chipOnBg, "--chip-on-fg" to c.chipOnFg,
        "--seg-on-bg" to c.segOnBg, "--rssi-strong" to c.rssiStrong, "--rssi-fair" to c.rssiFair, "--rssi-weak" to c.rssiWeak,
        "--rec" to c.rec,
    )
    val shadows = listOf("--shadow-float" to s.shadowFloat, "--shadow-raised" to s.shadowRaised, "--shadow-sheet" to s.shadowSheet, "--shadow-thumb" to s.shadowThumb)
    val cells = mutableListOf<GalleryCell>()
    colors.forEach { (n, col) -> cells += GalleryCell("Color", n) { Swatch(n, col) } }
    shadows.forEach { (n, sh) -> cells += GalleryCell("Shadow", n) { ShadowSwatch(sh) } }

    cells += GalleryCell("FloatButton", "menu") { FloatButton(Glyphs.menu, "Menu", noop) }
    cells += GalleryCell("FloatButton", "discover") { FloatButton(Glyphs.compass, "Discover", noop) }
    cells += GalleryCell("FloatButton", "back") { FloatButton(Glyphs.back, Strings.Back, noop) }
    cells += GalleryCell("FloatButton", "search") { FloatButton(Glyphs.search, "Search", noop) }
    cells += GalleryCell("FloatButton", "density on") { FloatButton(Glyphs.density, "Density", noop, on = true) }
    cells += GalleryCell("FloatButton", "density off") { FloatButton(Glyphs.density, "Density", noop) }
    cells += GalleryCell("FloatButton", "location warning") { FloatButton(Glyphs.locateOff, Strings.LocationProblem, noop, tint = c.warn) }

    cells += GalleryCell("IconButton", "Pause") { IconButton(Glyphs.pause, Strings.Pause, noop) }
    cells += GalleryCell("IconButton", "Resume") { IconButton(Glyphs.play, Strings.Resume, noop) }
    cells += GalleryCell("IconButton", "Stop") { IconButton(Glyphs.stop, Strings.Stop, noop, kind = IconButtonKind.Rec) }

    cells += GalleryCell("FilterButton", "off") { IconButton(Glyphs.filter, Strings.filterActive(0), noop) }
    cells += GalleryCell("FilterButton", "3 filters") { IconButton(Glyphs.filter, Strings.filterActive(3), noop, kind = IconButtonKind.Selected, badge = 3) }

    val g = GallerySamples
    cells += GalleryCell("DeviceRow", "normal", 412.dp, padded = false) { DeviceRow(g.normal.name, g.normal.mac, g.normal.rssi, g.normal.spark, noop) }
    cells += GalleryCell("DeviceRow", "stale", 412.dp, padded = false) { DeviceRow(g.stale.name, g.stale.mac, g.stale.rssi, g.stale.spark, noop, stale = true) }
    cells += GalleryCell("DeviceRow", "no name", 412.dp, padded = false) { DeviceRow(g.noName.name, g.noName.mac, g.noName.rssi, g.noName.spark, noop) }
    cells += GalleryCell("DeviceRow", "map list (no sparkline), selected", 412.dp, padded = false) {
        DeviceRow(g.normal.name, g.normal.mac, g.normal.rssi, null, noop, selected = true)
    }

    cells += GalleryCell("ProductCard and CategorySection", "in range") {
        ProductCard("Apple AirPods and Beats (Proximity Pairing)", Strings.productStatus(true, 2, 1, "just now"), "audio-device", noop)
    }
    cells += GalleryCell("ProductCard and CategorySection", "seen before") {
        ProductCard("Philips Hue Bluetooth lights", Strings.productStatus(true, 1, 0, "just now"), "lighting-control", noop)
    }
    cells += GalleryCell("ProductCard and CategorySection", "unidentified") {
        ProductCard(Strings.UnidentifiedDevices, Strings.productStatus(true, 8, 7, "just now"), null, noop)
    }
    cells += GalleryCell("ProductCard and CategorySection", "category section", 412.dp, padded = false) {
        CategorySection("Lighting") {
            ProductCard("Govee lighting and sensors", Strings.productStatus(true, 4, 4, "just now"), "lighting-control", noop)
            ProductCard("Philips Hue Bluetooth lights", Strings.productStatus(true, 1, 0, "just now"), "lighting-control", noop)
        }
    }

    cells += GalleryCell("Segmented", "Nearby") { Segmented(listOf(Strings.Nearby, Strings.All), 0, {}, Modifier.width(184.dp)) }
    cells += GalleryCell("Segmented", "All") { Segmented(listOf(Strings.Nearby, Strings.All), 1, {}, Modifier.width(184.dp)) }
    cells += GalleryCell("Segmented", "device tabs") { Segmented(listOf(Strings.Advertising, Strings.Gatt), 0, {}, Modifier.width(380.dp)) }
    cells += GalleryCell("Segmented", "sheet header") {
        Segmented(listOf(Strings.ThisArea, Strings.All), 1, {}, Modifier.width(184.dp), size = SegmentedSize.Header)
    }

    cells += GalleryCell("Button", "primary") { Button(Strings.DiscoverGatt, noop) }
    cells += GalleryCell("Button", "tonal") { Button("View on map", noop, kind = ButtonKind.Tonal) }
    cells += GalleryCell("Button", "small") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(Strings.Pause, noop, kind = ButtonKind.Tonal, small = true)
            Button(Strings.UploadNow, noop, kind = ButtonKind.Tonal, small = true)
        }
    }
    cells += GalleryCell("Button", "text") { TextButton(Strings.SearchEverywhere, noop) }
    cells += GalleryCell("Button", "disabled") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(Strings.Next, noop, enabled = false)
            Button(Strings.UploadNow, noop, kind = ButtonKind.Tonal, small = true, enabled = false)
            IconButton(Glyphs.pause, Strings.Pause, noop, enabled = false)
        }
    }

    cells += GalleryCell("Switch", "on") { Switch(true, {}) }
    cells += GalleryCell("Switch", "off") { Switch(false, {}) }
    cells += GalleryCell("Checkbox", "on") { Checkbox(true) }
    cells += GalleryCell("Checkbox", "off") { Checkbox(false) }

    cells += GalleryCell("SearchField", "empty") { SearchField("", {}, "Search products", Modifier.width(380.dp)) }
    cells += GalleryCell("SearchField", "with text") { SearchField("govee", {}, "Search products", Modifier.width(380.dp)) }

    cells += GalleryCell("Chip", "off") { Chip(Strings.SearchThisArea, false, noop) }
    cells += GalleryCell("Chip", "on") { Chip(Strings.Trackers, true, noop) }
    cells += GalleryCell("Chip", "Search this area") { Chip(Strings.SearchThisArea, false, noop, floating = true) }
    cells += GalleryCell("Chip", "chip row", 412.dp, padded = false) {
        ChipRow { listOf(Strings.All, Strings.Readable, Strings.Trackers, Strings.Vulnerable).forEachIndexed { i, l -> Chip(l, i == 0, noop) } }
    }

    cells += GalleryCell("ListItem", "History rows", 412.dp, padded = false) {
        Column {
            SectionLabel("Today · Oct 28")
            ListItem("6:05 PM", sub = "23 min · 1.8 km · 214 devices", chevron = true, onClick = noop)
            ListItem("12:10 PM", sub = "8 min · 41 devices", chevron = true, onClick = noop)
        }
    }
    cells += GalleryCell("ListItem", "Settings rows", 412.dp, padded = false) {
        Column {
            ListItem(Strings.WifiOnly, trailing = { Switch(true, {}) })
            ListItem(Strings.AppVersion, end = "0.1.0 (debug)")
        }
    }

    cells += GalleryCell("BlinkingDot", "recording") { BlinkingDot(paused = false) }
    cells += GalleryCell("BlinkingDot", "paused") { BlinkingDot(paused = true) }
    cells += GalleryCell("Handle and SheetFade", "handle", 412.dp, padded = false) { Handle() }
    cells += GalleryCell("Handle and SheetFade", "sheet fade over a list", 412.dp, padded = false) {
        Box(Modifier.background(c.panel)) {
            Column { DeviceRow(g.airpods.name, g.airpods.mac, g.airpods.rssi, g.airpods.spark, noop) }
            SheetFade(scrolled = true)
        }
    }
    cells += GalleryCell("Empty", "no results in this area", 300.dp) {
        EmptyText(Strings.NoResultsInArea) { TextButton(Strings.SearchEverywhere, noop) }
    }

    for (glyph in Glyphs.all) cells += GalleryCell("Icons", glyph.name) { Icon(glyph, glyph.name, size = 24.dp) }
    for ((id, entry) in CategoryIcons.table) cells += GalleryCell("Category icons", "$id (${entry.first})") { CategoryIcon(id, null, size = 24.dp) }
    cells += GalleryCell("Category icons", "unidentified (help)") { CategoryIcon(null, null, size = 24.dp, tint = c.t3) }
    cells += GalleryCell("Category icons", "other id (devices_other)") { CategoryIcon("not-a-category", null, size = 24.dp) }
    cells += GalleryCell("Category icons", "hero", padded = true) {
        Box(Modifier.size(200.dp, 120.dp).background(c.fill2, RoundedCornerShape(Concentric.hero(false))), contentAlignment = Alignment.Center) {
            CategoryIcon("lighting-control", null, size = 88.dp, tint = c.t2, strokeWidth = 1.2f)
        }
    }

    val t = CluetoothTheme.type
    cells += GalleryCell("Type", "styles", 412.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Body 15/24 · Govee lighting", style = t.body)
            Text("Title 17/650 · Settings", style = t.title)
            Text("Head 24/30 · Philips Hue", style = t.head)
            Text("Sub 13/20 · 72:CD:4C:4B:E4:6F", style = t.sub.tabular(), color = c.t2)
            Text("Small 12.5/18 · Today · Oct 28", style = t.small, color = c.t3)
            Text("Tabular ${Format.signed(-48)} ${Format.signed(-111)} 12:34", style = t.body.tabular())
            Text("Code 0201060aff4c00", style = t.code, color = c.t2)
        }
    }
    return cells
}

/** The gallery screen: the cells by section, wrapped like the prototype (a 20 dp gap, cells on `--bg`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignSystemGallery(modifier: Modifier = Modifier, cells: List<GalleryCell> = designSystemCells()) {
    val c = CluetoothTheme.colors
    Column(modifier.background(c.bg).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Cluetooth UI v2 components", style = CluetoothTheme.type.head)
        Text("Each :designsystem component in each state.", color = c.t2)
        for ((section, items) in cells.groupBy { it.section }) {
            Spacer(Modifier.height(28.dp))
            Text(section, style = CluetoothTheme.type.title)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items.forEach { GalleryCellBox(it) }
            }
        }
    }
}

/** One cell: the box (`.g-box`: `--bg`, radius 12, a 1 dp inset `--div` ring, 12 dp padding) and its label. */
@Composable
fun GalleryCellBox(cell: GalleryCell, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    Column(modifier.widthIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryBox(cell)
        Text(cell.label, style = CluetoothTheme.type.small, color = c.t3)
    }
}

/** The cell's box alone (what the screenshot tests capture). */
@Composable
fun GalleryBox(cell: GalleryCell, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .then(if (cell.width != null) Modifier.width(cell.width) else Modifier)
            .background(c.bg, shape)
            .border(1.dp, c.div, shape)
            .padding(if (cell.padded) 12.dp else 0.dp),
    ) { cell.content() }
}

/** A small interactive check that the gallery's stateful pieces work (used in the gallery app). */
@Composable
fun InteractiveSamples() {
    var seg by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(listOf(Strings.Nearby, Strings.All), seg, { seg = it }, Modifier.width(184.dp), size = SegmentedSize.Header)
        Switch(on, { on = it })
        SearchField(q, { q = it }, Strings.SearchPlaceholder, Modifier.fillMaxWidth())
    }
}
