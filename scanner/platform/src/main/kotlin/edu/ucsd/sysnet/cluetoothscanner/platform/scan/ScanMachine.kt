package edu.ucsd.sysnet.cluetoothscanner.platform.scan

/**
 * The scan session of a recording (docs/v2-api.md §4.1, SPEC V-1) as one immutable value and a pure reducer.
 * [BleScanner] is the runner: it turns Android callbacks into [ScanEvent]s, samples the [ScanEnv] facts when it
 * reduces, and executes the [ScanEffect]s. Nothing here touches Android or the clock, so every ordering is testable
 * on the JVM. Scan results never pass through here: they flow from the callback to [ScanIngest].
 *
 * - Every start path (start, Bluetooth back, a retry, a freed limiter slot, a restart) goes through `startOrWait`,
 *   so Android's start limit ([ScanStartLimiter.MAX_STARTS] per [ScanStartLimiter.WINDOW_MS]) covers them all.
 * - A scan always uses [ScanState.footer], the PHY the core's payload footer records; only a restart after a
 *   successful `configureScan` changes it, and a recording's environment ([ScanEvent.FooterDeclared]) resets it.
 * - Timers carry a [TimerToken]; a timer that was re-armed or cancelled meanwhile is ignored by the state.
 */
internal object ScanMachine {
    val RESTART = Millis(60_000)
    val RETRY = Millis(1_000)

    fun reduce(state: ScanState, event: ScanEvent, env: ScanEnv): ScanTransition = Builder(state, env).apply { on(event) }.build()

    /** 0 when a start is allowed at [now], else how long until the oldest counted start leaves the window. */
    fun limiterWait(starts: List<ElapsedMs>, now: ElapsedMs): Millis {
        val recent = starts.filter { now.since(it) < ScanStartLimiter.WINDOW_MS }
        if (recent.size < ScanStartLimiter.MAX_STARTS) return Millis(0)
        return Millis(ScanStartLimiter.WINDOW_MS - now.since(recent.minBy { it.value }))
    }

    private class Builder(var state: ScanState, val env: ScanEnv) {
        val effects = mutableListOf<ScanEffect>()

        fun build() = ScanTransition(state, effects.toList())

        fun on(event: ScanEvent) {
            when (event) {
                is ScanEvent.FooterDeclared -> state = state.copy(footer = event.phy)
                ScanEvent.Start -> start()
                ScanEvent.Stop -> stop()
                ScanEvent.BluetoothOff -> bluetoothOff()
                ScanEvent.BluetoothOn -> if (state.phase is ScanPhase.Waiting) startOrWait()
                is ScanEvent.StartFailed -> failed(event.generation, "startScan failed: ${event.message}")
                is ScanEvent.ScanFailed -> failed(event.generation, "scan failed with ${event.code}")
                is ScanEvent.TimerFired -> timer(event.kind, event.token)
                ScanEvent.ScreenOn -> screenOn()
                is ScanEvent.Configured -> configured(event.phy, event.error)
                is ScanEvent.EffectFailed -> effectFailed(event.effect, event.message)
            }
        }

        /** An effect threw in the runner: a failed start retries like a refused one; anything else is reported. */
        private fun effectFailed(effect: ScanEffect, message: String) {
            when (effect) {
                is ScanEffect.StartScan -> failed(effect.generation, "startScan failed: $message")
                is ScanEffect.Configure -> configured(effect.phy, message)
                ScanEffect.RegisterReceivers, ScanEffect.UnregisterReceivers, is ScanEffect.StopScan, is ScanEffect.Arm,
                is ScanEffect.Cancel,
                -> state = state.copy(error = "The scanner hit a problem ($message)")
            }
        }

        private fun start() {
            when (state.phase) {
                ScanPhase.Stopped -> {
                    effects += ScanEffect.RegisterReceivers
                    startOrWait()
                }
                is ScanPhase.Waiting -> startOrWait()
                is ScanPhase.Scanning -> Unit
            }
        }

        private fun stop() {
            when (val phase = state.phase) {
                ScanPhase.Stopped -> return
                is ScanPhase.Waiting -> Unit
                is ScanPhase.Scanning -> effects += ScanEffect.StopScan(phase.generation)
            }
            cancelTimers()
            effects += ScanEffect.UnregisterReceivers
            state = state.copy(phase = ScanPhase.Stopped)
        }

        private fun bluetoothOff() {
            when (state.phase) {
                ScanPhase.Stopped -> Unit
                is ScanPhase.Waiting, is ScanPhase.Scanning -> waitFor(Wait.BluetoothOff)
            }
        }

        /** The one start path: the limit first, then the radio, then the scan with the footer PHY. */
        private fun startOrWait() {
            cancel(TimerKind.Retry)
            cancel(TimerKind.LimiterSlot)
            val wait = limiterWait(state.starts, env.now)
            if (wait.value > 0) {
                // Over Android's start limit: start later, never drop the start.
                state = state.copy(phase = ScanPhase.Waiting(Wait.Limited))
                arm(TimerKind.LimiterSlot, wait)
                return
            }
            when (env.radio) {
                Radio.Off -> return waitFor(Wait.BluetoothOff)
                Radio.NoScanner -> return waitFor(Wait.NoScanner)
                Radio.On -> Unit
            }
            // Bluetooth back, an error retry or a resume never switch the PHY: the footer would no longer be true.
            val phy = state.footer ?: env.wanted
            val generation = state.nextGeneration
            state = state.copy(
                phase = ScanPhase.Scanning(generation, startedAt = env.now, skippedWhileScreenOff = false),
                footer = phy,
                nextGeneration = generation.next(),
                starts = (state.starts + env.now).filter { env.now.since(it) < ScanStartLimiter.WINDOW_MS },
                error = null,
            )
            effects += ScanEffect.StartScan(generation, phy)
            arm(TimerKind.Restart, RESTART)
        }

        /** The scan stops and waits; the recording stays open. Bluetooth off waits for STATE_ON, the rest retries. */
        private fun waitFor(wait: Wait) {
            when (val phase = state.phase) {
                is ScanPhase.Scanning -> effects += ScanEffect.StopScan(phase.generation)
                ScanPhase.Stopped, is ScanPhase.Waiting -> Unit
            }
            cancelTimers()
            state = state.copy(phase = ScanPhase.Waiting(wait))
            when (wait) {
                Wait.BluetoothOff -> Unit
                Wait.NoScanner, Wait.Limited, is Wait.Failed -> arm(TimerKind.Retry, RETRY)
            }
        }

        /** Android ended the scan itself (or refused it): nothing to stop, retry after [RETRY]. */
        private fun failed(generation: ScanGeneration, reason: String) {
            val phase = state.phase as? ScanPhase.Scanning ?: return
            if (phase.generation != generation) return
            cancelTimers()
            state = state.copy(phase = ScanPhase.Waiting(Wait.Failed(reason)))
            arm(TimerKind.Retry, RETRY)
        }

        private fun timer(kind: TimerKind, token: TimerToken) {
            if (state.timers[kind] != token) return // re-armed or cancelled meanwhile
            state = state.copy(timers = state.timers - kind)
            when (kind) {
                TimerKind.Retry, TimerKind.LimiterSlot -> if (state.phase is ScanPhase.Waiting) startOrWait()
                TimerKind.Restart -> restartTimer()
            }
        }

        private fun restartTimer() {
            val phase = state.phase as? ScanPhase.Scanning ?: return
            if (ScanRestartPolicy.periodicRestartNow(env.screenOn, env.now.since(phase.startedAt))) {
                restartNow()
            } else {
                state = state.copy(phase = phase.copy(skippedWhileScreenOff = true))
                arm(TimerKind.Restart, RESTART)
            }
        }

        private fun screenOn() {
            val phase = state.phase as? ScanPhase.Scanning ?: return
            val footer = state.footer ?: env.wanted
            if (ScanRestartPolicy.screenOnRestartNow(phase.skippedWhileScreenOff, footer == Phy.Coded, env.wanted == Phy.Coded)) {
                restartNow()
            }
        }

        /**
         * Restarts with the current settings; a Coded PHY change first tells the core, so the footer stays true. A
         * restart over the start limit keeps the running scan and comes back when it may start.
         */
        private fun restartNow() {
            val phase = state.phase as? ScanPhase.Scanning ?: return
            if (state.configuring != null) return
            val wait = limiterWait(state.starts, env.now)
            if (wait.value > 0) return arm(TimerKind.Restart, wait)
            if (env.wanted == state.footer) {
                effects += ScanEffect.StopScan(phase.generation)
                cancelTimers()
                startOrWait()
                return
            }
            cancel(TimerKind.Restart)
            state = state.copy(configuring = env.wanted)
            effects += ScanEffect.Configure(env.wanted)
        }

        /**
         * `configureScan` finished. Success changes the footer (also while paused or stopped, so the next start uses
         * it) and applies it with a restart; a failure keeps the running scan and its settings, is reported, and
         * re-arms the restart.
         */
        private fun configured(phy: Phy, error: String?) {
            state = state.copy(configuring = null)
            state = if (error == null) state.copy(footer = phy) else state.copy(error = "The scan settings could not change ($error)")
            val phase = state.phase as? ScanPhase.Scanning ?: return
            if (error == null) {
                effects += ScanEffect.StopScan(phase.generation)
                cancelTimers()
                startOrWait()
            } else {
                arm(TimerKind.Restart, RESTART)
            }
        }

        private fun arm(kind: TimerKind, delay: Millis) {
            val token = state.nextToken
            state = state.copy(timers = state.timers + (kind to token), nextToken = token.next())
            effects += ScanEffect.Arm(kind, token, delay)
        }

        private fun cancel(kind: TimerKind) {
            if (kind !in state.timers) return
            state = state.copy(timers = state.timers - kind)
            effects += ScanEffect.Cancel(kind)
        }

        private fun cancelTimers() = TimerKind.entries.forEach(::cancel)
    }
}

/** A point on the monotonic clock (`SystemClock.elapsedRealtime`). */
@JvmInline
internal value class ElapsedMs(val value: Long) {
    fun since(earlier: ElapsedMs): Long = value - earlier.value
}

/** A duration in milliseconds. */
@JvmInline
internal value class Millis(val value: Long)

/** Identifies one `startScan` registration; callbacks of any other generation are stale. */
@JvmInline
internal value class ScanGeneration(val value: Long) {
    fun next() = ScanGeneration(value + 1)
}

/** Identifies one armed timer; a fired timer whose token is no longer armed is ignored. */
@JvmInline
internal value class TimerToken(val value: Long) {
    fun next() = TimerToken(value + 1)
}

/** The scan PHY: Coded means `PHY_LE_ALL_SUPPORTED` ("Scan LE Coded PHY" on), OneM means `PHY_LE_1M`. */
internal enum class Phy {
    Coded,
    OneM,
    ;

    companion object {
        fun of(coded: Boolean) = if (coded) Coded else OneM
    }
}

internal data class ScanState(
    val phase: ScanPhase = ScanPhase.Stopped,
    /** The PHY the core's footer records; null until a recording declared one or the first scan chose it. */
    val footer: Phy? = null,
    /** The PHY a `configureScan` in flight is setting, or null. */
    val configuring: Phy? = null,
    /** Start times inside the limiter window. */
    val starts: List<ElapsedMs> = emptyList(),
    val timers: Map<TimerKind, TimerToken> = emptyMap(),
    val nextGeneration: ScanGeneration = ScanGeneration(1),
    val nextToken: TimerToken = TimerToken(1),
    /** A scanner problem the user should see; cleared by the next start. */
    val error: String? = null,
) {
    /** Recording while the scan waits for Bluetooth, a scanner or a retry (`RecorderState.scannerDeferred`). */
    val deferred: Boolean
        get() = when (val phase = phase) {
            ScanPhase.Stopped, is ScanPhase.Scanning -> false
            is ScanPhase.Waiting -> when (phase.wait) {
                Wait.BluetoothOff, Wait.NoScanner, is Wait.Failed -> true
                Wait.Limited -> false
            }
        }
}

internal sealed interface ScanPhase {
    data object Stopped : ScanPhase
    data class Waiting(val wait: Wait) : ScanPhase
    data class Scanning(val generation: ScanGeneration, val startedAt: ElapsedMs, val skippedWhileScreenOff: Boolean) : ScanPhase
}

internal sealed interface Wait {
    data object BluetoothOff : Wait
    data object NoScanner : Wait
    data object Limited : Wait
    data class Failed(val reason: String) : Wait
}

internal enum class TimerKind { Restart, Retry, LimiterSlot }

internal enum class Radio { On, Off, NoScanner }

/** The facts the runner samples when it reduces an event. */
internal data class ScanEnv(val now: ElapsedMs, val radio: Radio, val wanted: Phy, val screenOn: Boolean)

internal sealed interface ScanEvent {
    data class FooterDeclared(val phy: Phy) : ScanEvent
    data object Start : ScanEvent
    data object Stop : ScanEvent
    data object BluetoothOff : ScanEvent
    data object BluetoothOn : ScanEvent
    data class StartFailed(val generation: ScanGeneration, val message: String?) : ScanEvent
    data class ScanFailed(val generation: ScanGeneration, val code: Int) : ScanEvent
    data class TimerFired(val kind: TimerKind, val token: TimerToken) : ScanEvent
    data object ScreenOn : ScanEvent

    /** `configureScan(phy)` finished; [error] is null on success. */
    data class Configured(val phy: Phy, val error: String?) : ScanEvent

    /** The runner could not execute [effect] (any exception, as a value). */
    data class EffectFailed(val effect: ScanEffect, val message: String) : ScanEvent
}

internal sealed interface ScanEffect {
    data object RegisterReceivers : ScanEffect
    data object UnregisterReceivers : ScanEffect
    data class StartScan(val generation: ScanGeneration, val phy: Phy) : ScanEffect
    data class StopScan(val generation: ScanGeneration) : ScanEffect
    data class Arm(val kind: TimerKind, val token: TimerToken, val delay: Millis) : ScanEffect
    data class Cancel(val kind: TimerKind) : ScanEffect
    data class Configure(val phy: Phy) : ScanEffect
}

internal data class ScanTransition(val state: ScanState, val effects: List<ScanEffect>)
