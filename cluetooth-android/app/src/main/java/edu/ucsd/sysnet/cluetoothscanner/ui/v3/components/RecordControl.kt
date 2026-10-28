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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalTexts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra

/**
 * Where the one record control is drawn. [Round] is the idle red button and [Expanded] the
 * recording controls, both at the bottom-right; [Pill] is the floating top-right form.
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
 * Shadow of the record family (round button, expanded controls, pill, scroll-to-top, the map's
 * floating surfaces): 2 dp, a slight shadow close to the button, lighter than the FAB default of 6 dp.
 * The round button passes it through `FloatingActionButtonDefaults.elevation` (4 dp while pressed);
 * the surfaces use it as `shadowElevation`, so all three states cast the same shadow.
 */
val FabElevation = 2.dp

/** Pill height; pages reserve room for it with [LocalRecordPillInset]. */
val RecordPillHeight = 32.dp

/**
 * The bottom-right form: a bare red button with a white dot while idle, the recording controls
 * (blinking dot, time, pause/resume, stop) while recording on a page with room for them.
 */
@Composable
fun BottomRecordControl(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier = Modifier) {
    val active = state.phase != RecorderPhase.Idle
    AnimatedContent(
        targetState = active,
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
        transitionSpec = {
            (fadeIn(tween(160, delayMillis = 60)) togetherWith fadeOut(tween(90)))
                .using(SizeTransform(clip = false))
        },
        label = "record-bottom",
    ) { isActive ->
        if (!isActive) {
            val description = stringResource(R.string.record_start)
            FloatingActionButton(
                onClick = callbacks.onStart,
                modifier = Modifier.semantics { contentDescription = description },
                shape = CircleShape,
                containerColor = MaterialTheme.extra.record,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = FabElevation,
                    pressedElevation = 4.dp,
                    focusedElevation = FabElevation,
                    hoveredElevation = FabElevation,
                ),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
            }
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = FabElevation,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.height(56.dp).padding(start = 20.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val paused = state.phase == RecorderPhase.Paused
                    PulseDot(paused = paused, size = 10)
                    Spacer(Modifier.width(10.dp))
                    // Frozen while paused, and dimmed.
                    Box(Modifier.alpha(if (paused) 0.5f else 1f)) { ElapsedTime(state, fontSize = 20, blinkWhenPaused = false) }
                    Spacer(Modifier.width(20.dp))
                    PauseResumeButton(paused, callbacks)
                    Spacer(Modifier.width(8.dp))
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
        modifier = modifier.height(RecordPillHeight).semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = FabElevation,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(paused = state.phase == RecorderPhase.Paused, size = 8)
            Spacer(Modifier.width(7.dp))
            ElapsedTime(state, fontSize = 13)
        }
    }
}

/** Red while recording and pulsing; gray when paused. */
@Composable
private fun PulseDot(paused: Boolean, size: Int) {
    val alpha = if (paused || LocalStaticRender.current) 1f else {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(
            0.35f, 1f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse-alpha",
        )
        a
    }
    Box(
        Modifier.size(size.dp).alpha(alpha).clip(CircleShape)
            .background(if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.extra.record),
    )
}

/** Monospace time; frozen and slowly blinking while paused. */
@Composable
private fun ElapsedTime(state: RecorderUiState, fontSize: Int, blinkWhenPaused: Boolean = true) {
    val paused = state.phase == RecorderPhase.Paused
    val alpha = if (!paused || !blinkWhenPaused || LocalStaticRender.current) 1f else {
        val t = rememberInfiniteTransition(label = "blink")
        val a by t.animateFloat(
            1f, 0.25f,
            infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "blink-alpha",
        )
        a
    }
    Text(
        text = formatClock(state.elapsedSeconds),
        modifier = Modifier.alpha(alpha),
        fontFamily = MonoFamily,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PauseResumeButton(paused: Boolean, callbacks: RecorderCallbacks) {
    FilledTonalIconButton(onClick = if (paused) callbacks.onResume else callbacks.onPause) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            stringResource(if (paused) R.string.record_resume else R.string.record_pause),
        )
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.extra.record, contentColor = Color.White),
    ) {
        Icon(Icons.Filled.Stop, stringResource(R.string.record_stop))
    }
}

/** The bar opened by tapping the pill: time, row count, pause/resume and stop. */
@Composable
fun RecordControlBar(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier = Modifier) {
    val paused = state.phase == RecorderPhase.Paused
    SheetSurface(modifier) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(paused, size = 10)
                Spacer(Modifier.width(10.dp))
                ElapsedTime(state, fontSize = 28)
                Spacer(Modifier.weight(1f))
                Text(
                    rowsText(state.sightings, LocalTexts.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = if (paused) callbacks.onResume else callbacks.onPause,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (paused) R.string.record_resume else R.string.record_pause))
                }
                Button(
                    onClick = callbacks.onStop,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.extra.record, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.record_stop))
                }
            }
        }
    }
}

/** Shown after stop: "Saved · 23 min · 1.4k rows" with Add note and View. */
@Composable
fun RecordSavedSnackbar(
    durationSeconds: Int,
    sightings: Int,
    modifier: Modifier = Modifier,
    onAddNote: () -> Unit = {},
    onView: () -> Unit = {},
) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.record_saved,
                    formatDuration(durationSeconds, LocalTexts.current), rowsText(sightings, LocalTexts.current),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            val action = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
            TextButton(onClick = onAddNote, colors = action) { Text(stringResource(R.string.action_add_note)) }
            TextButton(onClick = onView, colors = action) { Text(stringResource(R.string.action_view)) }
        }
    }
}

/** Small round button stacked directly above the full-size record button. */
@Composable
fun ScrollToTopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = FabElevation,
            pressedElevation = 4.dp,
            focusedElevation = FabElevation,
            hoveredElevation = FabElevation,
        ),
    ) {
        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_scroll_to_top), modifier = Modifier.size(22.dp))
    }
}

/** A recording that could not be saved: what happened, retry, dismiss. */
@Composable
fun RecordProblemSnackbar(message: String, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            val action = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
            TextButton(onClick = onRetry, colors = action) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = onDismiss, colors = action) { Text(stringResource(R.string.action_close)) }
        }
    }
}
