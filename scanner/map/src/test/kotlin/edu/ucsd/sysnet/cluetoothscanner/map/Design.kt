package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.map.style.MapStyleSpec
import java.io.File

/** The committed design exports (design/ui-v2), as the build copies them into the assets. */
object Design {
    private val dir = File(requireNotNull(System.getProperty("cluetooth.designDir")) { "cluetooth.designDir not set" })
    val styleJson: String by lazy { File(dir, "map-style.json").readText() }
    val style: MapStyleSpec by lazy { MapStyleSpec.parse(styleJson) }
    val tokens: MapTokens by lazy { MapTokens.parse(File(dir, "tokens.json").readText()) }
}
