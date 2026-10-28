package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataStatus
import edu.ucsd.sysnet.cluetoothscanner.service.ScanOperatingState

/** The one-line notices under a page header, in one place so the copy stays exact. */
object Notices {
    const val BLUETOOTH_OFF = "蓝牙已关闭，打开后继续扫描"
    const val SCANNER_UNAVAILABLE = "蓝牙扫描暂时不可用，正在重试"
    const val PAUSED_BACKPRESSURE = "正在保存，稍后自动恢复扫描"
    const val SCAN_FAILED = "无法开始扫描，请检查蓝牙权限"
    const val RECORDING_WAITS_FOR_BLUETOOTH = "录制在等待蓝牙恢复"
    const val DATA_UNAVAILABLE = "本地数据库不可用，暂时不记录历史"
    const val IDENTIFICATION_UNAVAILABLE = "产品识别不可用"
    const val NO_FIX = "还没有定位，轨迹暂时不会记录"
    const val STALE_FIX = "定位已超过 1 分钟没有更新，轨迹暂停"

    /** A fix older than this while recording is reported. */
    const val STALE_FIX_AFTER_MS = 60_000L

    fun scanner(state: ScanOperatingState): String? = when (state) {
        ScanOperatingState.BLUETOOTH_OFF -> BLUETOOTH_OFF
        ScanOperatingState.SCANNER_UNAVAILABLE -> SCANNER_UNAVAILABLE
        ScanOperatingState.PAUSED_BACKPRESSURE -> PAUSED_BACKPRESSURE
        ScanOperatingState.FAILED -> SCAN_FAILED
        ScanOperatingState.SCANNING, ScanOperatingState.STOPPED -> null
    }

    fun data(state: LocalDataStatus.State): String? = when (state) {
        LocalDataStatus.State.UNAVAILABLE -> DATA_UNAVAILABLE
        LocalDataStatus.State.IDENTIFICATION_UNAVAILABLE -> IDENTIFICATION_UNAVAILABLE
        LocalDataStatus.State.STARTING, LocalDataStatus.State.READY -> null
    }

    /**
     * Only while recording: no fix at all, or the last one older than [STALE_FIX_AFTER_MS].
     * [fixAgeMs] is null when there is no fix.
     */
    fun location(recording: Boolean, fixAgeMs: Long?): String? = when {
        !recording -> null
        fixAgeMs == null -> NO_FIX
        fixAgeMs > STALE_FIX_AFTER_MS -> STALE_FIX
        else -> null
    }
}
