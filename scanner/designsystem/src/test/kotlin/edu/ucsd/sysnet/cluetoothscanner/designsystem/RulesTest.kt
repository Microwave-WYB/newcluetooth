package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.CategoryIcons
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.sparklineSegments
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    /** G-13a and its examples (and C-7, D-7, R-4, R-5, P-5, S-2, T-3). */
    @Test fun concentricCorners() {
        assertEquals(34.dp, Concentric.sheetRadius)
        assertEquals(30.dp, Concentric.panelRadius)
        assertEquals(22.dp, Concentric.panelRow)
        assertEquals(18.dp, Concentric.card(tablet = false))
        assertEquals(6.dp, Concentric.card(tablet = true))
        assertEquals(22.dp, Concentric.hero(tablet = false))
        assertEquals(10.dp, Concentric.hero(tablet = true))
        assertEquals(22.dp, Concentric.recorderPanel(tablet = false))
        assertEquals(28.dp, Concentric.recorderPanel(tablet = true))
        assertEquals(4.dp, Concentric.recorderButtonInset(tablet = false))
        assertEquals(8.dp, Concentric.recorderButtonInset(tablet = true))
        assertEquals(28.dp, Concentric.popover)
        assertEquals(Concentric.popover, Concentric.outer(edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css.BtnSmall.height / 2, Tokens.popPad))
        assertEquals(14.dp, Concentric.cornerButton(tablet = false))
        assertEquals(Tokens.panelPad, Tokens.panelInset)
        assertEquals(Tokens.popPad, 12.dp)
    }

    /** G-7: durations and easing from the tokens; reduced motion makes every spec instant. */
    @Test fun motion() {
        val m = Motion(reduced = false)
        assertEquals(180, (m.fast<Float>() as TweenSpec).durationMillis)
        assertEquals(240, (m.std<Float>() as TweenSpec).durationMillis)
        assertEquals(320, (m.page<Float>() as TweenSpec).durationMillis)
        assertEquals(360, (m.morph<Float>() as TweenSpec).durationMillis)
        assertEquals(200, (m.fade<Float>() as TweenSpec).durationMillis)
        assertEquals(Tokens.ease, (m.std<Float>() as TweenSpec).easing)
        assertEquals(1000, m.blinkMs)
        assertEquals(800, m.spinMs)
        val r = Motion(reduced = true)
        listOf(r.fast<Float>(), r.std(), r.page(), r.morph(), r.fade(), r.linear(800), r.spec(100, 50)).forEach {
            assertTrue(it is SnapSpec)
        }
        assertEquals(0, r.duration(320))
    }

    /** G-5: the CSS blur radius maps onto BlurMaskFilter's σ = 0.57735 r + 0.5 so both are σ = B / 2. */
    @Test fun shadowBlur() {
        for (b in listOf(2f, 16f, 32f, 64f)) {
            val r = cssBlurToBlurMaskRadius(b)
            assertEquals(b / 2f, 0.57735f * r + 0.5f, 1e-4f)
        }
        assertEquals(0f, cssBlurToBlurMaskRadius(0f))
        assertEquals(16.dp, Tokens.lightShadows.shadowFloat.blur)
        assertEquals((-6).dp, Tokens.lightShadows.shadowSheet.y)
    }

    /** C-6: the sparkline geometry equals app.js; gaps break the line. */
    @Test fun sparkline() {
        val s = ByteArray(30) { if (it in 10..12) 0 else (50 + it).toByte() }
        val segs = sparklineSegments(s, 56f, 22f)
        assertEquals(2, segs.size)
        assertEquals(10, segs[0].size)
        assertEquals(17, segs[1].size)
        // k = 0, r = −50: x 0, y = 1 + (−30 + 50) / 70 · 20
        assertEquals(Offset(0f, 1f + 20f / 70f * 20f), segs[0][0])
        // clamping: −120 draws at −100 (the bottom, 21), −10 at −30 (the top, 1)
        val c = sparklineSegments(byteArrayOf(120.toByte(), 10), 56f, 22f).single()
        assertEquals(21f, c[0].y, 1e-4f)
        assertEquals(1f, c[1].y, 1e-4f)
        assertEquals(56f, c[1].x, 1e-4f)
    }

    /** C-17 and docs/v2-api.md §3.4: the 21 categories, the unidentified and the generic glyph. */
    @Test fun categoryIcons() {
        assertEquals(21, CategoryIcons.table.size)
        val symbols = mapOf(
            "asset-tracker" to "sell", "positioning-beacon" to "sensors", "fleet-telematics" to "local_shipping",
            "vehicle-equipment" to "directions_car", "wearable" to "watch", "audio-device" to "headphones",
            "telephony" to "call", "camera" to "photo_camera", "security-and-access" to "lock",
            "lighting-control" to "lightbulb", "smart-appliance" to "kitchen", "personal-care" to "self_care",
            "medical-device" to "medical_services", "reader-terminal" to "point_of_sale", "printer" to "print",
            "power-energy" to "bolt", "environmental-monitor" to "thermostat", "display" to "tv",
            "input-peripheral" to "keyboard", "sports-equipment" to "fitness_center", "traffic-control" to "traffic",
        )
        assertEquals(symbols, CategoryIcons.table.mapValues { it.value.first })
        // the C-17 glyphs of the prototype's categories
        assertEquals(Glyphs.headphones, CategoryIcons.glyph("audio-device"))
        assertEquals(Glyphs.bulb, CategoryIcons.glyph("lighting-control"))
        assertEquals(Glyphs.thermo, CategoryIcons.glyph("environmental-monitor"))
        assertEquals(Glyphs.tag, CategoryIcons.glyph("asset-tracker"))
        assertEquals(Glyphs.watch, CategoryIcons.glyph("wearable"))
        assertEquals(Glyphs.car, CategoryIcons.glyph("vehicle-equipment"))
        assertEquals(Glyphs.medical, CategoryIcons.glyph("medical-device"))
        assertEquals(Glyphs.unknown, CategoryIcons.glyph(null))
        assertEquals(Glyphs.unknown, CategoryIcons.glyph("unidentified"))
        assertEquals(Glyphs.devicesOther, CategoryIcons.glyph("something-new"))
    }
}
