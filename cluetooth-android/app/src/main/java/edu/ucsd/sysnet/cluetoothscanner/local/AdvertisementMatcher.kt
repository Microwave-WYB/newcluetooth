package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.core.generated.AdvAddressType
import edu.ucsd.sysnet.cluetoothscanner.core.generated.RuleMatcher

enum class AddressType { PUBLIC, RANDOM }

/** Which wiki pages an advertisement matches. */
interface AdvertisementMatcher {
    /** The wiki bundle version the rules come from. */
    val bundleVersion: String

    /**
     * Ids of the matching pages, in bundle order. [addr] (`AA:BB:CC:DD:EE:FF`) and
     * [addrType] feed the rules' `addr` conditions; without an address those never match.
     */
    fun match(raw: ByteArray, addr: String? = null, addrType: AddressType? = null): List<String>
}

/** The Rust matcher (cluetooth-core `RuleMatcher`) over the app's wiki bundle. */
class NativeAdvertisementMatcher private constructor(private val native: RuleMatcher) : AdvertisementMatcher {
    override val bundleVersion: String = native.bundleVersion()

    /** `page-id: reason` for rules the matcher skipped; empty for a healthy bundle. */
    val ruleErrors: List<String> = native.ruleErrors()

    override fun match(raw: ByteArray, addr: String?, addrType: AddressType?): List<String> =
        native.matchAdvertisement(
            raw,
            addr,
            when (addrType) {
                AddressType.PUBLIC -> AdvAddressType.PUBLIC
                AddressType.RANDOM -> AdvAddressType.RANDOM
                null -> null
            },
        )

    companion object {
        fun fromBundleJson(bundleJson: String) = NativeAdvertisementMatcher(RuleMatcher.fromBundleJson(bundleJson))
    }
}
