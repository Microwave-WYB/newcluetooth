package edu.ucsd.sysnet.cluetoothscanner.ble

/**
 * What screens read from one stored advertisement: manufacturer data with Apple's company id (0x004C)
 * and the advertised local name ([name]; complete over shortened). Read-only; it decides what a screen
 * shows (a device row's name, the "Hide unnamed Apple devices" filter), never what is scanned,
 * recorded, stored or uploaded.
 */
data class AdTraits(val apple: Boolean, val named: Boolean, val name: String? = null) {
    companion object {
        const val APPLE_COMPANY_ID = 0x004C

        fun of(raw: ByteArray): AdTraits {
            var apple = false
            var complete: String? = null
            var shortened: String? = null
            for (s in BleParser.parseAdvertisementData(raw).structures) {
                val type = s.type.toInt()
                val d = s.data
                if (type == 0xFF && d.size >= 2 && ((d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)) == APPLE_COMPANY_ID) apple = true
                val text = d.decodeToString().takeIf { it.isNotBlank() }
                if (type == 0x09 && text != null && complete == null) complete = text
                if (type == 0x08 && text != null && shortened == null) shortened = text
            }
            val name = complete ?: shortened
            return AdTraits(apple, name != null, name)
        }

        /**
         * A device the filter hides: Apple's company id in any of its advertisements and a name in none
         * (nor from the platform, [name]), whatever the wiki rules matched it to.
         */
        fun unnamedApple(name: String?, traits: Iterable<AdTraits>): Boolean =
            name.isNullOrBlank() && traits.any { it.apple } && traits.none { it.named }
    }
}
