package edu.ucsd.sysnet.cluetoothscanner.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The Wireless Wiki as the app sees it: wiki-bundle.json, written at build time by
// scanner/tools/wiki-bundle. Rules stay in the JSON the native matcher reads;
// they are not modelled here.

@Serializable
enum class PageKind {
    @SerialName("device") DEVICE,
    @SerialName("platform") PLATFORM,
    @SerialName("identifier") IDENTIFIER,
}

@Serializable
data class WikiCategory(
    val id: String,
    /** English label from the wiki's vocabulary. */
    val label: String,
    @SerialName("label_zh") val labelZh: String,
)

@Serializable
data class WikiPage(
    /** `<kind>--<slug>`, the wiki directory name. Stable; every other table refers to it. */
    val id: String,
    val title: String,
    val kind: PageKind,
    @SerialName("one_line") val oneLine: String,
    /** Category ids; empty for pages with none (protocols, identifiers). */
    val categories: List<String> = emptyList(),
    val entity: String? = null,
    @SerialName("has_rule") val hasRule: Boolean,
)

@Serializable
data class WikiCatalog(
    val schema: Int,
    /** Changes whenever anything in the bundle changes; a new value triggers a re-match. */
    val version: String,
    /**
     * Changes only when a rule changes (not for a retitled page or a relabelled
     * category); stored matches are re-done on this. Older bundles lack it: [version] then.
     */
    @SerialName("rules_version") val rulesVersion: String? = null,
    val categories: List<WikiCategory>,
    val pages: List<WikiPage>,
) {
    private val pageById by lazy { pages.associateBy { it.id } }
    private val categoryById by lazy { categories.associateBy { it.id } }

    fun page(id: String): WikiPage? = pageById[id]
    fun category(id: String): WikiCategory? = categoryById[id]

    companion object {
        const val SUPPORTED_SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(bundleJson: String): WikiCatalog {
            val catalog = json.decodeFromString<WikiCatalog>(bundleJson)
            require(catalog.schema == SUPPORTED_SCHEMA) {
                "Unsupported wiki bundle schema ${catalog.schema}; expected $SUPPORTED_SCHEMA"
            }
            return catalog
        }
    }
}

/** How far the local data layer got; see [LocalData.status]. */
data class LocalDataStatus(val state: State, val message: String? = null) {
    enum class State {
        /** Opening the database and loading the wiki bundle. Queries answer once this ends. */
        STARTING,

        /** Everything works. */
        READY,

        /** The database works but the bundle or the native matcher did not load: nothing is identified. */
        IDENTIFICATION_UNAVAILABLE,

        /** The database could not be opened: every query is empty and nothing is recorded. */
        UNAVAILABLE,
    }

    companion object {
        val READY_STATUS = LocalDataStatus(State.READY)
    }
}

/** A position with its accuracy radius. */
data class GeoPoint(val lat: Double, val lon: Double, val accuracyMeters: Double?)

// Nearby ---------------------------------------------------------------------------------

/** One identified product among the devices in range right now. */
data class ProductInRange(
    val page: WikiPage,
    val deviceCount: Int,
    val strongestRssi: Int?,
    val lastSeenMs: Long,
)

/**
 * The devices heard within the last [windowMs]. Matching is by advertisement
 * content (and address when known); a device heard with several different
 * advertisements counts for each product they identify.
 */
data class InRangeSnapshot(
    val atMs: Long,
    val windowMs: Long,
    val products: List<ProductInRange>,
    val unidentifiedDeviceCount: Int,
    val totalDeviceCount: Int,
)

/** A product seen at least once, from the payload store (kept across sessions). */
data class SeenProduct(
    val page: WikiPage,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Distinct advertisements that matched the page. */
    val payloadCount: Int,
    /** How many times those advertisements were heard. */
    val seenCount: Long,
)

/** Previously seen products of one category; [category] is null for pages without one. */
data class CategoryProducts(val category: WikiCategory?, val products: List<SeenProduct>)

/**
 * A group of unidentified advertisements sharing a coarse [structure] (company field, else first
 * 16-bit service UUID, else local-name prefix, else AD shape; see [StructureKey]) that are heard now.
 */
data class UnknownInRange(
    val structure: StructureKey,
    /**
     * Unidentified devices heard within the window with an advertisement of this structure. A device that
     * alternates advertisements of different structures counts in each group, so the groups can add up to
     * more than [InRangeSnapshot.unidentifiedDeviceCount].
     */
    val deviceCount: Int,
    /** The strongest of those devices' latest RSSI values; null when none reported one. */
    val strongestRssi: Int?,
    val lastSeenMs: Long,
)

/** A group of advertisements no page identifies, from the payload store (kept 30 days after last heard). */
data class SeenUnknown(
    val structure: StructureKey,
    /** Distinct advertisements of this structure that are still unidentified. */
    val payloadCount: Int,
    /** How many times those advertisements were heard. */
    val seenCount: Long,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/**
 * A latitude/longitude rectangle. Inclusive on all sides. [minLon] greater than [maxLon] means the
 * rectangle crosses the antimeridian (it covers [minLon, 180] and [-180, maxLon]).
 */
data class GeoBounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    init {
        require(minLat.isFinite() && minLon.isFinite() && maxLat.isFinite() && maxLon.isFinite()) { "bounds must be finite" }
        require(minLat <= maxLat) { "minLat $minLat is above maxLat $maxLat" }
    }

    val crossesAntimeridian: Boolean get() = minLon > maxLon
}

// Recordings -----------------------------------------------------------------------------

data class Recording(
    /** The core's scan session id. */
    val id: String,
    val startedAtMs: Long,
    /** Null while the recording is active. */
    val endedAtMs: Long?,
    val durationMs: Long,
    val distanceMeters: Double,
    /** Distinct addresses heard while recording. */
    val deviceCount: Int,
    val active: Boolean,
)

data class RecordedDevice(
    val addr: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val strongestRssi: Int?,
    /** Minutes in which the device was heard. */
    val minuteCount: Int,
    /** Pages matched by any of its advertisements (payload-level matching). */
    val pageIds: List<String>,
    /** Where the strongest located sample was heard. */
    val bestLocation: GeoPoint?,
)

/** One minute of one device with one advertisement. */
data class Sighting(
    /** Start of the minute, epoch milliseconds. */
    val minuteMs: Long,
    val addr: String,
    /** SHA-256 of the advertisement, lowercase hex. */
    val payloadHash: String,
    val recordingId: String,
    val bestRssi: Int?,
    /** Where the best sample of the minute was heard; null when no fix was available. */
    val location: GeoPoint?,
    val sampleCount: Int,
    val pageIds: List<String>,
)

data class RoutePoint(val timeMs: Long, val lat: Double, val lon: Double, val accuracyMeters: Double)

// Bundle updates ---------------------------------------------------------------------------

/** A stored advertisement that no page matched before the latest bundle and one does now. */
data class NewlyIdentified(val payloadHash: String, val pageIds: List<String>, val seenCount: Long)

/**
 * Result of re-matching every stored advertisement after the bundle changed.
 * "Devices newly identified" in the UI is [newlyIdentified].size (distinct
 * advertisements; the store keeps no per-address history outside recordings).
 */
data class RematchReport(
    val id: Long,
    /** The matching key before and after: the bundle's rules version plus the matcher engine (`<rules>+m<engine>`). */
    val fromVersion: String?,
    val toVersion: String,
    val ranAtMs: Long,
    val payloadsChecked: Int,
    val newlyIdentified: List<NewlyIdentified>,
    /** Set once [LocalData.acknowledgeRematch] was called. */
    val acknowledged: Boolean,
)
