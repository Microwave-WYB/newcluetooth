package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.local.CategoryProducts
import edu.ucsd.sysnet.cluetoothscanner.local.InRangeSnapshot
import edu.ucsd.sysnet.cluetoothscanner.local.Recording
import edu.ucsd.sysnet.cluetoothscanner.local.ProductInRange
import edu.ucsd.sysnet.cluetoothscanner.local.WikiPage
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingMode
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DiscoverUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductHeaderState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductRowState
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

object DiscoverMapper {
    fun iconOf(categoryId: String?) = when (categoryId) {
        "asset-tracker" -> CategoryIcon.Tracker
        "fleet-telematics", "vehicle-equipment" -> CategoryIcon.Car
        "wearable", "sports-equipment", "personal-care" -> CategoryIcon.Watch
        "audio-device" -> CategoryIcon.Headphones
        "lighting-control" -> CategoryIcon.Light
        "environmental-monitor" -> CategoryIcon.Sensor
        "power-energy" -> CategoryIcon.Power
        "smart-appliance" -> CategoryIcon.Home
        "medical-device" -> CategoryIcon.Medical
        "security-and-access" -> CategoryIcon.Beacon
        else -> CategoryIcon.Unknown
    }

    /** "just now", "5 min ago", "2 h ago", "3 d ago", then the date. */
    fun ago(ms: Long, nowMs: Long, texts: Texts, zone: ZoneId = ZoneId.systemDefault()): String {
        val minutes = ((nowMs - ms).coerceAtLeast(0) / 60_000).toInt()
        return when {
            minutes < 1 -> texts.string(R.string.time_just_now)
            minutes < 60 -> texts.string(R.string.time_minutes_ago, minutes)
            minutes < 24 * 60 -> texts.string(R.string.time_hours_ago, minutes / 60)
            minutes < 7 * 24 * 60 -> texts.string(R.string.time_days_ago, minutes / (24 * 60))
            else -> texts.monthDay(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
        }
    }

    fun header(page: WikiPage) = ProductHeaderState(page.id, page.title, page.oneLine, iconOf(page.categories.firstOrNull()))

    /**
     * Each product once (its first category only gives the icon): in range now, strongest first; and
     * every product seen, most recently seen first, the ones in range saying so.
     */
    fun map(
        recorder: RecorderUiState,
        inRange: InRangeSnapshot?,
        seen: List<CategoryProducts>,
        texts: Texts,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DiscoverUiState {
        val live = inRange?.products.orEmpty().distinctBy { it.page.id }
        val liveById = live.associateBy { it.page.id }
        fun inRangeRow(p: ProductInRange) = ProductRowState(
            p.page.id, p.page.title, iconOf(p.page.categories.firstOrNull()),
            texts.string(R.string.product_in_range, texts.plural(R.plurals.devices, p.deviceCount, p.deviceCount)), p.strongestRssi,
        )
        val nearby = live.sortedByDescending { it.strongestRssi ?: Int.MIN_VALUE }.map(::inRangeRow)
        val lastSeen = HashMap<String, Pair<WikiPage, Long>>()
        fun note(page: WikiPage, ms: Long) {
            val old = lastSeen[page.id]
            if (old == null || ms > old.second) lastSeen[page.id] = page to ms
        }
        live.forEach { note(it.page, it.lastSeenMs) }
        seen.forEach { group -> group.products.forEach { note(it.page, it.lastSeenMs) } }
        val all = lastSeen.values.sortedByDescending { it.second }.map { (page, ms) ->
            liveById[page.id]?.let(::inRangeRow) ?: ProductRowState(
                page.id, page.title, iconOf(page.categories.firstOrNull()),
                texts.string(R.string.product_last_seen, ago(ms, nowMs, texts, zone)), null,
            )
        }
        return DiscoverUiState(recorder, nearby, all)
    }
}

object RecordingMapper {
    fun summary(r: Recording, upload: GatewaySessionUploadState?, note: String?, hiddenDevices: Int = 0) = RecordingSummaryState(
        id = r.id,
        startedAt = Instant.ofEpochMilli(r.startedAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime(),
        durationSeconds = (r.durationMs / 1000).toInt(),
        distanceMeters = r.distanceMeters.toInt(),
        deviceCount = (r.deviceCount - hiddenDevices).coerceAtLeast(0),
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
        /** Per recording, the devices the display filter leaves out of its count. */
        hiddenDevices: Map<String, Int> = emptyMap(),
    ): RecordingsUiState {
        val all = recordings.map { summary(it, uploads[it.id], notes[it.id], hiddenDevices[it.id] ?: 0) to it.active }
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
