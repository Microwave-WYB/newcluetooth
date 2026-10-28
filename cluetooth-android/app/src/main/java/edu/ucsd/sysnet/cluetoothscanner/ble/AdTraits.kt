package edu.ucsd.sysnet.cluetoothscanner.ble

/**
 * The two things the "Hide unnamed Apple devices" display filter looks at in one advertisement:
 * manufacturer data with Apple's company id (0x004C), and an advertised local name. Read-only; it
 * decides what a screen shows, never what is scanned, recorded, stored or uploaded.
 */
data class AdTraits(val apple: Boolean, val named: Boolean) {
    companion object {
        const val APPLE_COMPANY_ID = 0x004C

        fun of(raw: ByteArray): AdTraits {
            var apple = false
            var named = false
            for (s in BleParser.parseAdvertisementData(raw).structures) {
                val type = s.type.toInt()
                val d = s.data
                if (type == 0xFF && d.size >= 2 && ((d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)) == APPLE_COMPANY_ID) apple = true
                if ((type == 0x08 || type == 0x09) && d.decodeToString().isNotBlank()) named = true
            }
            return AdTraits(apple, named)
        }

        /**
         * A device the filter hides: Apple's company id in any of its advertisements and a name in none
         * (nor from the platform, [name]), whatever the wiki rules matched it to.
         */
        fun unnamedApple(name: String?, traits: Iterable<AdTraits>): Boolean =
            name.isNullOrBlank() && traits.any { it.apple } && traits.none { it.named }
    }
}
