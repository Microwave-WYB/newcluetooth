package edu.ucsd.sysnet.cluetoothscanner.fake.core

import edu.ucsd.sysnet.cluetoothscanner.api.core.PageKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.PageRef

/**
 * The fake's wiki bundle (§3.4): the data.js products as bundle pages, with categories and traits from the wiki
 * vocabularies (`schema/device.schema.json` and `traits.schema.json` at wirelesswiki `d1b543b`). Page ids follow the
 * wiki's `<kind>--<slug>` convention; the ids of families the wiki already has are the real ones.
 */
object FakeCatalog {
    class Category(val id: String, val title: String)

    /** The 21 categories, in schema order. */
    val CATEGORIES = listOf(
        Category("asset-tracker", "Asset tracker"),
        Category("positioning-beacon", "Positioning beacon"),
        Category("fleet-telematics", "Fleet telematics"),
        Category("vehicle-equipment", "Vehicle equipment"),
        Category("wearable", "Wearable"),
        Category("audio-device", "Audio device"),
        Category("telephony", "Telephony"),
        Category("camera", "Camera"),
        Category("security-and-access", "Security and access"),
        Category("lighting-control", "Lighting control"),
        Category("smart-appliance", "Smart appliance"),
        Category("personal-care", "Personal care"),
        Category("medical-device", "Medical device"),
        Category("reader-terminal", "Reader or terminal"),
        Category("printer", "Printer"),
        Category("power-energy", "Power and energy"),
        Category("environmental-monitor", "Environmental monitor"),
        Category("display", "Display"),
        Category("input-peripheral", "Input peripheral"),
        Category("sports-equipment", "Sports equipment"),
        Category("traffic-control", "Traffic control"),
    )
    val CATEGORY = CATEGORIES.associateBy { it.id }

    /** §3.4: the Trackers preset. */
    val TRACKER_CATEGORIES = setOf("asset-tracker")

    const val WIKI_URL = "https://wirelesswiki.org/p/"
    const val UNIDENTIFIED = "unidentified"

    class Page(
        val id: String, val kind: PageKind, val title: String, val oneLine: String?,
        val categories: List<String>, val traits: List<String>, val readable: Boolean,
        val vulnerabilities: List<String>,
        /** data.js product id, or null for pages without one (vulnerability pages). */
        val product: String?,
    ) {
        val ref get() = PageRef(id, kind, title)
        val tracker get() = categories.any { it in TRACKER_CATEGORIES }
        val vulnerable get() = vulnerabilities.isNotEmpty()
    }

    private val PROTOTYPE_CATEGORY = mapOf(
        "audio" to "audio-device", "lighting" to "lighting-control", "sensors" to "environmental-monitor",
        "trackers" to "asset-tracker", "wearables" to "wearable", "vehicles" to "vehicle-equipment",
        "medical" to "medical-device",
    )

    private val PAGE_ID = mapOf(
        "govee" to "device--govee-h-model-code-names",
        "airpods" to "device--apple-airpods-proximity-pairing",
        "jbl" to "device--jbl-flip-charge-speakers",
        "bose" to "device--bose-le-audio-names",
        "hue" to "device--philips-hue-bluetooth",
        "xiaomi" to "platform--xiaomi-mibeacon",
        "switchbot" to "device--switchbot-meter",
        "tile" to "device--tile-feed",
        "smarttag" to "device--samsung-galaxy-smarttag",
        "fitbit" to "device--fitbit-pixel-watch",
        "tesla" to "device--tesla-phone-key",
        "inkbird" to "device--inkbird-ibs-th",
        "nanoleaf" to "device--nanoleaf-essentials",
        "dexcom" to "device--dexcom-g7",
        "ouraring" to "device--oura-ring",
    )

    private val TRAITS = mapOf(
        "airpods" to listOf("rotating-address"),
        "tile" to listOf("no-local-name", "rotating-address"),
        "smarttag" to listOf("no-local-name", "rotating-address", "encrypted-payload"),
        "tesla" to listOf("phone-advertiser", "identifier-in-name"),
        "govee" to listOf("identifier-in-name"),
    )

    const val DEMO_VULNERABILITY = "vulnerability--demo-phone-key-relay"

    /** Bundle page order: the data.js products, then the vulnerability page. */
    val PAGES: List<Page> = FakeData.PRODUCTS.map { p ->
        Page(
            id = PAGE_ID.getValue(p.id),
            kind = if (PAGE_ID.getValue(p.id).startsWith("platform--")) PageKind.Platform else PageKind.Device,
            title = p.name, oneLine = p.description,
            categories = listOf(PROTOTYPE_CATEGORY.getValue(p.category)),
            traits = TRAITS[p.id].orEmpty(), readable = p.readable,
            vulnerabilities = if (p.vulnerable) listOf(DEMO_VULNERABILITY) else emptyList(),
            product = p.id,
        )
    } + Page(DEMO_VULNERABILITY, PageKind.Vulnerability, "Phone-key relay (demo)", "A made-up vulnerability page.",
        emptyList(), emptyList(), false, emptyList(), null)

    val PAGE = PAGES.associateBy { it.id }
    val PAGE_ORDER = PAGES.withIndex().associate { (i, p) -> p.id to i }

    fun pageOfProduct(product: String?): Page? = product?.let { PAGE[PAGE_ID.getValue(it)] }
}
