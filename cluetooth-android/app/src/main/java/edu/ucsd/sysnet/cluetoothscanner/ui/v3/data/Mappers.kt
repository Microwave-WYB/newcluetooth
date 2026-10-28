package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.local.CategoryProducts
import edu.ucsd.sysnet.cluetoothscanner.local.InRangeSnapshot
import edu.ucsd.sysnet.cluetoothscanner.local.Recording
import edu.ucsd.sysnet.cluetoothscanner.local.SeenUnknown
import edu.ucsd.sysnet.cluetoothscanner.local.StructureKey
import edu.ucsd.sysnet.cluetoothscanner.local.StructureKind
import edu.ucsd.sysnet.cluetoothscanner.local.UnknownInRange
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCatalog
import edu.ucsd.sysnet.cluetoothscanner.local.WikiPage
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingMode
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyCategoryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductItemState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingDayState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingSummaryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.UploadStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun RecordingState.toRecorderUi() = RecorderUiState(
    phase = when (mode) {
        RecordingMode.IDLE -> RecorderPhase.Idle
        RecordingMode.RECORDING -> RecorderPhase.Recording
        RecordingMode.PAUSED -> RecorderPhase.Paused
    },
    elapsedSeconds = (elapsedMs / 1000).toInt(),
    sightings = rowCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
)

object NearbyMapper {
    const val OTHER = "other"
    const val UNIDENTIFIED = "unknown"

    /** Item ids of unidentified structures; they have no map. */
    const val UNKNOWN_PREFIX = "unknown:"

    private fun iconOf(categoryId: String) = when (categoryId) {
        "asset-tracker" -> CategoryIcon.Tracker
        "fleet-telematics", "vehicle-equipment" -> CategoryIcon.Car
        "wearable", "sports-equipment", "personal-care" -> CategoryIcon.Watch
        "audio-device" -> CategoryIcon.Headphones
        "lighting-control", "smart-appliance", "environmental-monitor", "power-energy" -> CategoryIcon.Home
        "medical-device" -> CategoryIcon.Medical
        "security-and-access" -> CategoryIcon.Beacon
        else -> CategoryIcon.Unknown
    }

    /** The product's one line: its page's summary up to the first sentence end. */
    private fun subtitle(page: WikiPage): String = page.oneLine.substringBefore(". ").trimEnd('.')

    /** "Company 0x004C", "Service FEED", "Name ELK…", "Shape 01/1,FF/7". */
    fun structureLabel(key: StructureKey, texts: Texts): String = when (key.kind) {
        StructureKind.COMPANY ->
            key.companyId?.let { texts.string(R.string.structure_company_id, it) } ?: texts.string(R.string.structure_company, key.value)
        StructureKind.SERVICE_UUID -> texts.string(R.string.structure_service, key.value)
        StructureKind.LOCAL_NAME -> texts.string(R.string.structure_name, key.value)
        StructureKind.AD_SHAPE -> if (key.value == "-") texts.string(R.string.structure_none) else texts.string(R.string.structure_shape, key.value)
    }

    fun map(
        recorder: RecorderUiState,
        inRange: InRangeSnapshot?,
        seen: List<CategoryProducts>,
        catalog: WikiCatalog?,
        texts: Texts,
        zone: ZoneId = ZoneId.systemDefault(),
        unknownNow: List<UnknownInRange> = emptyList(),
        unknownSeen: List<SeenUnknown> = emptyList(),
    ): NearbyUiState {
        class Cat(val id: String, val title: String) {
            val items = LinkedHashMap<String, ProductItemState>()
        }
        val cats = LinkedHashMap<String, Cat>()
        catalog?.categories?.forEach { cats[it.id] = Cat(it.id, it.label) }
        cats[OTHER] = Cat(OTHER, texts.string(R.string.category_other))

        fun keysOf(page: WikiPage) = page.categories.ifEmpty { listOf(OTHER) }
        fun cat(key: String) = cats.getOrPut(key) { Cat(key, catalog?.category(key)?.label ?: key) }

        // Products in range first, strongest first.
        inRange?.products.orEmpty().sortedByDescending { it.strongestRssi ?: Int.MIN_VALUE }.forEach { p ->
            keysOf(p.page).forEach { k ->
                cat(k).items[p.page.id] = ProductItemState(p.page.id, p.page.title, subtitle(p.page), p.strongestRssi)
            }
        }
        // Then what was seen before, newest first, without a signal dot.
        seen.forEach { group ->
            val key = group.category?.id ?: OTHER
            group.products.forEach { sp ->
                val c = cat(key)
                if (sp.page.id !in c.items) {
                    val day = Instant.ofEpochMilli(sp.lastSeenMs).atZone(zone).toLocalDate()
                    c.items[sp.page.id] = ProductItemState(sp.page.id, sp.page.title, texts.string(R.string.last_seen, texts.monthDay(day)), null)
                }
            }
        }
        // Unidentified advertisements by coarse structure: those heard now first (strongest first), then the rest.
        val unknown = LinkedHashMap<String, ProductItemState>()
        unknownNow.sortedByDescending { it.strongestRssi ?: Int.MIN_VALUE }.forEach { u ->
            unknown[u.structure.key] = ProductItemState(
                "$UNKNOWN_PREFIX${u.structure.key}", texts.string(R.string.structure_label_count, structureLabel(u.structure, texts), u.deviceCount),
                texts.plural(R.plurals.devices_in_range, u.deviceCount, u.deviceCount), u.strongestRssi,
            )
        }
        unknownSeen.forEach { u ->
            if (u.structure.key !in unknown) {
                val day = Instant.ofEpochMilli(u.lastSeenMs).atZone(zone).toLocalDate()
                unknown[u.structure.key] = ProductItemState(
                    "$UNKNOWN_PREFIX${u.structure.key}", texts.string(R.string.structure_label_count, structureLabel(u.structure, texts), u.payloadCount),
                    texts.string(R.string.last_seen, texts.monthDay(day)), null,
                )
            }
        }
        return NearbyUiState(
            recorder = recorder,
            categories = cats.values.filter { it.items.isNotEmpty() }.map {
                NearbyCategoryState(it.id, it.title, iconOf(it.id), it.items.values.toList())
            },
            unrecognized = NearbyCategoryState(UNIDENTIFIED, texts.string(R.string.category_unidentified), CategoryIcon.Unknown, unknown.values.toList()),
        )
    }
}

object RecordingMapper {
    fun summary(r: Recording, upload: GatewaySessionUploadState?, note: String?) = RecordingSummaryState(
        id = r.id,
        startedAt = Instant.ofEpochMilli(r.startedAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime(),
        durationSeconds = (r.durationMs / 1000).toInt(),
        distanceMeters = r.distanceMeters.toInt(),
        deviceCount = r.deviceCount,
        // Never claim an upload that the core has not reported: no state is "unknown", not "uploaded".
        upload = when {
            r.active -> UploadStatus.Uploading
            upload == null -> UploadStatus.Unknown
            upload == GatewaySessionUploadState.UPLOADED -> UploadStatus.Uploaded
            upload == GatewaySessionUploadState.FAILED -> UploadStatus.Failed
            upload == GatewaySessionUploadState.EMPTY -> UploadStatus.Empty
            else -> UploadStatus.Uploading
        },
        note = note?.takeIf { it.isNotBlank() },
    )

    fun state(
        recorder: RecorderUiState,
        recordings: List<Recording>,
        uploads: Map<String, GatewaySessionUploadState>,
        notes: Map<String, String>,
        texts: Texts,
        today: LocalDate = LocalDate.now(),
    ): RecordingsUiState {
        val all = recordings.map { summary(it, uploads[it.id], notes[it.id]) to it.active }
        val current = all.firstOrNull { it.second }?.first
        // Finished recordings that produced no data ("No data") are hidden here; nothing is deleted.
        val days = all.filter { !it.second && it.first.upload != UploadStatus.Empty }.map { it.first }
            .groupBy { it.startedAt.toLocalDate() }
            .map { (date, items) -> RecordingDayState(dayLabel(date, today, texts), items.sortedByDescending { it.startedAt }) }
            .sortedByDescending { day -> day.recordings.first().startedAt }
        return RecordingsUiState(recorder, current, days)
    }

    fun dayLabel(date: LocalDate, today: LocalDate, texts: Texts): String = when {
        date == today -> texts.string(R.string.today_day, texts.monthDay(date))
        date == today.minusDays(1) -> texts.string(R.string.yesterday_day, texts.monthDay(date))
        date.year != today.year -> texts.fullDate(date)
        else -> texts.monthDay(date)
    }
}
