package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.core.generated.AdvAddressType
import edu.ucsd.sysnet.cluetoothscanner.core.generated.RuleMatcher

enum class AddressType { PUBLIC, RANDOM }

/** Which wiki pages an advertisement matches. */
interface AdvertisementMatcher {
    /** The wiki bundle version the rules come from. */
    val bundleVersion: String

    /**
     * Version of the evaluation semantics (cluetooth-core `MATCHER_ENGINE_VERSION`). Stored matches are
     * re-done when it changes, even if the rules did not.
     */
    val engineVersion: Int get() = 1

    /** `page-id: reason` for rules that were skipped; empty for a healthy bundle. */
    val ruleErrors: List<String> get() = emptyList()

    /**
     * Whether any rule looks at the address. When none does, [match] depends on the bytes
     * alone, so callers can cache results by payload and skip the address.
     */
    val usesAddressRules: Boolean get() = false

    /**
     * Ids of the matching pages, in bundle order. [addr] (`AA:BB:CC:DD:EE:FF`) and
     * [addrType] feed the rules' `addr` conditions; without an address those never match.
     */
    fun match(raw: ByteArray, addr: String? = null, addrType: AddressType? = null): List<String>
}

/** The Rust matcher (cluetooth-core `RuleMatcher`) over the app's wiki bundle. */
class NativeAdvertisementMatcher private constructor(private val native: RuleMatcher) : AdvertisementMatcher {
    override val bundleVersion: String = native.bundleVersion()

    override val ruleErrors: List<String> = native.ruleErrors()
    override val usesAddressRules: Boolean = native.usesAddressRules()
    override val engineVersion: Int = native.engineVersion().toInt()

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
