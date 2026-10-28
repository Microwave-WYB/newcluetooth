package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionState

/** What the UI says when the coordinator comes to rest at STOPPED. */
object RecordingOutcome {
    enum class Kind {
        /** A recording ran and was sealed. */
        Saved,

        /** The core session could not be opened (STARTING went straight to STOPPED). */
        StartFailed,

        /** The scanner refused to start after the session opened (STARTING, then FINALIZING, then STOPPED). */
        ScannerRejected,

        /** Nothing to report, e.g. a stop this ViewModel only joined midway. */
        None,
    }

    /**
     * [startSeen]: this ViewModel saw the start begin. A ViewModel created while a recording was being
     * sealed sees FINALIZING then STOPPED without a start, which is not a failed start.
     */
    fun atStop(previous: ScanTransitionState, startSeen: Boolean, hasSummary: Boolean): Kind = when {
        hasSummary -> Kind.Saved
        previous == ScanTransitionState.STARTING -> Kind.StartFailed
        previous == ScanTransitionState.FINALIZING && startSeen -> Kind.ScannerRejected
        else -> Kind.None
    }
}
