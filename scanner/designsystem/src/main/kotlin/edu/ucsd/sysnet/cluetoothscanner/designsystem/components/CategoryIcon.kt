package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyph
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * The category icon table (docs/v2-api.md §3.4, C-17): the 21 wiki categories to their glyph, drawn in the C-17
 * stroke style. Each entry names the Material Symbol it stands for. Unidentified is the circled question mark, and
 * any other id the generic "other devices" glyph.
 */
object CategoryIcons {
    val table: Map<String, Pair<String, Glyph>> = linkedMapOf(
        "asset-tracker" to ("sell" to Glyphs.tag),
        "positioning-beacon" to ("sensors" to Glyphs.sensors),
        "fleet-telematics" to ("local_shipping" to Glyphs.truck),
        "vehicle-equipment" to ("directions_car" to Glyphs.car),
        "wearable" to ("watch" to Glyphs.watch),
        "audio-device" to ("headphones" to Glyphs.headphones),
        "telephony" to ("call" to Glyphs.call),
        "camera" to ("photo_camera" to Glyphs.camera),
        "security-and-access" to ("lock" to Glyphs.lock),
        "lighting-control" to ("lightbulb" to Glyphs.bulb),
        "smart-appliance" to ("kitchen" to Glyphs.kitchen),
        "personal-care" to ("self_care" to Glyphs.selfCare),
        "medical-device" to ("medical_services" to Glyphs.medical),
        "reader-terminal" to ("point_of_sale" to Glyphs.pointOfSale),
        "printer" to ("print" to Glyphs.print),
        "power-energy" to ("bolt" to Glyphs.bolt),
        "environmental-monitor" to ("thermostat" to Glyphs.thermo),
        "display" to ("tv" to Glyphs.tv),
        "input-peripheral" to ("keyboard" to Glyphs.keyboard),
        "sports-equipment" to ("fitness_center" to Glyphs.fitness),
        "traffic-control" to ("traffic" to Glyphs.traffic),
    )

    /** `help`: unidentified (no page). */
    val unidentified: Glyph = Glyphs.unknown

    /** `devices_other`: a category id the table does not know. */
    val fallback: Glyph = Glyphs.devicesOther

    /** The glyph of a category id; null means unidentified. A product with several categories passes its first. */
    fun glyph(categoryId: String?): Glyph = when (categoryId) {
        null, "unidentified" -> unidentified
        else -> table[categoryId]?.second ?: fallback
    }
}

/**
 * `CategoryIcon(categoryId, pageIcon)`: the page's own product icon (an asset path from the wiki bundle, Gitea #190)
 * takes precedence; otherwise the category glyph ([CategoryIcons]). [size] is the glyph box (`--icon` by default;
 * the hero uses `--hero-icon` and [strokeWidth] 1.2).
 */
@Composable
fun CategoryIcon(
    categoryId: String?,
    pageIcon: String?,
    modifier: Modifier = Modifier,
    size: Dp = Tokens.icon,
    tint: Color = Color.Unspecified,
    strokeWidth: Float = GlyphStroke,
) {
    val image = pageIcon?.let { rememberAssetImage(it) }
    if (image != null) {
        Image(image, null, modifier.size(size), contentScale = ContentScale.Fit)
    } else {
        Icon(CategoryIcons.glyph(categoryId), null, modifier, size = size, tint = tint, strokeWidth = strokeWidth)
    }
}

@Composable
private fun rememberAssetImage(path: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
}
