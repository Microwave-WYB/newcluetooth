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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.LocalStaticRender
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.MonoFamily
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.extra

enum class RecordPlacement { Full, Mini }

data class RecorderCallbacks(
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** Tapping the mini pill. */
    val onOpenControls: () -> Unit = {},
)

/**
 * The one global record button. [Full] is the round button that grows into a pill while
 * recording; [Mini] is the icon-sized form for the top bar. The shell moves it between the
 * two with a shared-bounds transition.
 */
@Composable
fun RecordControl(
    state: RecorderUiState,
    placement: RecordPlacement,
    callbacks: RecorderCallbacks,
    modifier: Modifier = Modifier,
) {
    when (placement) {
        RecordPlacement.Full -> FullRecordControl(state, callbacks, modifier)
        RecordPlacement.Mini -> MiniRecordControl(state, callbacks, modifier)
    }
}

@Composable
private fun FullRecordControl(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier) {
    val red = MaterialTheme.extra.record
    val active = state.phase != RecorderPhase.Idle
    AnimatedContent(
        targetState = active,
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
        transitionSpec = {
            (fadeIn(tween(160, delayMillis = 60)) togetherWith fadeOut(tween(90)))
                .using(SizeTransform(clip = false))
        },
        label = "record-full",
    ) { isActive ->
        if (!isActive) {
            // A bare red disc, no container.
            Box(
                Modifier
                    .size(60.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(red)
                    .clickable(onClick = callbacks.onStart),
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.height(60.dp).padding(start = 20.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulseDot(paused = state.phase == RecorderPhase.Paused, size = 10)
                    Spacer(Modifier.width(10.dp))
                    ElapsedTime(state, fontSize = 20)
                    Spacer(Modifier.width(20.dp))
                    PauseResumeButton(state.phase == RecorderPhase.Paused, callbacks)
                    Spacer(Modifier.width(8.dp))
                    StopButton(callbacks.onStop)
                }
            }
        }
    }
}

@Composable
private fun MiniRecordControl(state: RecorderUiState, callbacks: RecorderCallbacks, modifier: Modifier) {
    if (state.phase == RecorderPhase.Idle) {
        Box(
            modifier.size(44.dp).clip(CircleShape).clickable(onClick = callbacks.onStart),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.extra.record))
        }
    } else {
        Box(modifier.height(44.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = callbacks.onOpenControls)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(paused = state.phase == RecorderPhase.Paused, size = 8)
                Spacer(Modifier.width(7.dp))
                ElapsedTime(state, fontSize = 13)
            }
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
private fun ElapsedTime(state: RecorderUiState, fontSize: Int) {
    val paused = state.phase == RecorderPhase.Paused
    val alpha = if (!paused || LocalStaticRender.current) 1f else {
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
private fun RoundControl(icon: ImageVector, description: String, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PauseResumeButton(paused: Boolean, callbacks: RecorderCallbacks) {
    RoundControl(
        icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
        description = if (paused) "继续" else "暂停",
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        tint = MaterialTheme.colorScheme.onSurface,
        onClick = if (paused) callbacks.onResume else callbacks.onPause,
    )
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    RoundControl(Icons.Filled.Stop, "停止", MaterialTheme.extra.record, Color.White, onClick)
}

/** The bar opened by tapping the mini pill: time, count, pause/resume and stop. */
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
                    if (paused) "已暂停 · ${formatCount(state.sightings)} 条" else "录制中 · ${formatCount(state.sightings)} 条",
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
                    Text(if (paused) "继续" else "暂停")
                }
                Button(
                    onClick = callbacks.onStop,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.extra.record, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("结束录制")
                }
            }
        }
    }
}

/** Shown after stop: "已保存 · 23 分钟 · 1.4k 条" with 加备注 and 查看. */
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
                "已保存 · ${formatDuration(durationSeconds)} · ${formatCount(sightings)} 条",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            val action = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
            TextButton(onClick = onAddNote, colors = action) { Text("加备注") }
            TextButton(onClick = onView, colors = action) { Text("查看") }
        }
    }
}

/** Small round button stacked directly above the full-size record button. */
@Composable
fun ScrollToTopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.KeyboardArrowUp, "回到顶部", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}
