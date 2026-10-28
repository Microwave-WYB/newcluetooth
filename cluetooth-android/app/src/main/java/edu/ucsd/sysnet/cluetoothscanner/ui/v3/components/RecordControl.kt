package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.TabularNumbers

/**
 * Where the one record control is drawn. [Round] is the idle record button and [Expanded] the
 * recording controls, both bottom-right; [Pill] is the floating top-right form.
 */
enum class RecordPlacement { Round, Expanded, Pill }

data class RecorderCallbacks(
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** Tapping the pill. */
    val onOpenControls: () -> Unit = {},
)

/**
 * Idle: the round button. Recording: the expanded controls when the page has room for them
 * ([roomForExpanded]: the Scan page with nothing covering it), otherwise the floating pill.
 */
fun recordPlacement(phase: RecorderPhase, roomForExpanded: Boolean): RecordPlacement = when {
    phase == RecorderPhase.Idle -> RecordPlacement.Round
    roomForExpanded -> RecordPlacement.Expanded
    else -> RecordPlacement.Pill
}

/**
 * The expanded control and the pill sit on a stock [Surface] with the resting elevation of the
 * lowered FAB (FloatingActionButtonDefaults.loweredElevation(), 1 dp) that the round button uses,
 * so the three forms cast the same light shadow close to the button.
 */
private val FabRestingElevation = 1.dp

/**
 * The bottom-right form: the stock [FloatingActionButton] in the error (record) color with a white
 * dot while idle; while recording on a page with room, a [Surface] holding the blinking dot, the
 * time and stock icon buttons for pause/resume and stop.
 */
@Composable
fun BottomRecordControl(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier = Modifier) {
    val active = state.phase != RecorderPhase.Idle
    AnimatedContent(
        targetState = active,
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
        transitionSpec = { (fadeIn(tween(160, delayMillis = 60)) togetherWith fadeOut(tween(90))).using(SizeTransform(clip = false)) },
        label = "record-bottom",
    ) { isActive ->
        if (!isActive) {
            val description = stringResource(R.string.record_start)
            FloatingActionButton(
                onClick = callbacks.onStart,
                modifier = Modifier.semantics { contentDescription = description },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                elevation = FloatingActionButtonDefaults.loweredElevation(),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(LocalContentColor.current))
            }
        } else {
            val paused = state.phase == RecorderPhase.Paused
            Surface(shape = CircleShape, tonalElevation = FabRestingElevation, shadowElevation = FabRestingElevation) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(paused)
                    Spacer(Modifier.width(10.dp))
                    // Frozen while paused, and dimmed.
                    Text(
                        formatClock(state.elapsedSeconds),
                        Modifier.alpha(if (paused) 0.5f else 1f),
                        style = MaterialTheme.typography.titleLarge.merge(TabularNumbers),
                    )
                    Spacer(Modifier.width(12.dp))
                    PauseResumeButton(paused, callbacks)
                    StopButton(callbacks.onStop)
                }
            }
        }
    }
}

/** The compact form for the top-right corner: a blinking dot and the time, nothing else. */
@Composable
fun RecordPill(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.record_controls)
    Surface(
        onClick = callbacks.onOpenControls,
        modifier = modifier.semantics { contentDescription = description },
        shape = CircleShape,
        tonalElevation = FabRestingElevation,
        shadowElevation = FabRestingElevation,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(state.phase == RecorderPhase.Paused, size = 8)
            Spacer(Modifier.width(8.dp))
            ElapsedTime(state, MaterialTheme.typography.labelLarge)
        }
    }
}

/** Red while recording and blinking; gray and steady when paused. */
@Composable
private fun PulseDot(paused: Boolean, size: Int = 10) {
    val alpha = if (paused || LocalStaticRender.current) 1f else {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse-alpha")
        a
    }
    Box(
        Modifier.size(size.dp).alpha(alpha).clip(CircleShape)
            .background(if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error),
    )
}

/** The time; frozen and slowly blinking while paused. */
@Composable
private fun ElapsedTime(state: RecorderUiState, style: TextStyle) {
    val paused = state.phase == RecorderPhase.Paused
    val alpha = if (!paused || LocalStaticRender.current) 1f else {
        val t = rememberInfiniteTransition(label = "blink")
        val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "blink-alpha")
        a
    }
    Text(formatClock(state.elapsedSeconds), Modifier.alpha(alpha), style = style.merge(TabularNumbers))
}

@Composable
private fun PauseResumeButton(paused: Boolean, callbacks: RecorderCallbacks) {
    FilledTonalIconButton(onClick = if (paused) callbacks.onResume else callbacks.onPause) {
        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (paused) R.string.record_resume else R.string.record_pause))
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    FilledIconButton(onClick = onClick) {
        Icon(Icons.Filled.Stop, stringResource(R.string.record_stop))
    }
}

/** What the control sheet (opened from the pill) holds: time, rows, pause/resume and stop. */
@Composable
fun RecordControlSheetContent(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier = Modifier) {
    val paused = state.phase == RecorderPhase.Paused
    Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(paused)
            Spacer(Modifier.width(12.dp))
            ElapsedTime(state, MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text(rowsText(state.sightings, LocalTexts.current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = if (paused) callbacks.onResume else callbacks.onPause, modifier = Modifier.weight(1f)) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(if (paused) R.string.record_resume else R.string.record_pause))
            }
            Button(onClick = callbacks.onStop, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Stop, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.record_stop))
            }
        }
    }
}

/** Scroll to top on the Scan list: a stock small FAB with the lowered elevation; it only scrolls, it never re-sorts. */
@Composable
fun ScrollToTopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, modifier = modifier, elevation = FloatingActionButtonDefaults.loweredElevation()) {
        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_scroll_to_top))
    }
}
