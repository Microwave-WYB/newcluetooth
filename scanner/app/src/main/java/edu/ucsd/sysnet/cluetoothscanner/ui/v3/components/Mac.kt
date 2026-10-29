package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily

/**
 * A MAC address in monospace with narrow colons, so it reads C8:0F:10:3A:91:5E instead of
 * spreading out. A monospace colon takes a full cell, so the colons are set smaller (a cell
 * scales with its font size) and letter spacing is zero.
 */
fun AnnotatedString.Builder.appendMac(mac: String) {
    withStyle(SpanStyle(fontFamily = MonoFamily, letterSpacing = 0.sp)) {
        mac.forEach { c ->
            if (c == ':') withStyle(SpanStyle(fontSize = 0.55.em)) { append(c) } else append(c)
        }
    }
}

fun macText(mac: String): AnnotatedString = buildAnnotatedString { appendMac(mac) }
