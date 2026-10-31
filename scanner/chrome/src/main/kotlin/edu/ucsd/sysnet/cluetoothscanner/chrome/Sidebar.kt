package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Icon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ListItem
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SectionLabel
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.plainClickable
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.pressedFill
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.CssEnv
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import java.util.TimeZone

/** docs/v2-api.md §5.4: the sidebar's sessions list (v2.0: single selection only). */
data class SessionsListState(
    val current: SessionSummary?,
    val all: AllSessionsSummary,
    val days: List<DayGroup>,
    val selected: Scope?,
) {
    companion object {
        /**
         * L-2: the history grouped by day, newest first, each group labelled "Today · Oct 28", "Yesterday · Oct 27",
         * then "Oct 25". Sessions with no data never appear, and the current session is not part of the history.
         */
        fun build(
            current: SessionSummary?,
            all: AllSessionsSummary,
            finished: List<SessionSummary>,
            selected: Scope?,
            nowMs: Long,
            zone: TimeZone = TimeZone.getDefault(),
        ): SessionsListState {
            val shown = finished.filter { it.deviceCount > 0 && it.id != current?.id }.sortedByDescending { it.startedMs }
            val days = shown.groupBy { Format.dayKey(it.startedMs, zone) }.values.map { group ->
                DayGroup(Format.dayLabel(group.first().startedMs, nowMs, zone), group)
            }
            return SessionsListState(current?.takeIf { it.deviceCount > 0 || it.endedMs == null }, all, days, selected)
        }
    }
}

data class DayGroup(val label: String, val sessions: List<SessionSummary>)

private fun Scope?.isSession(id: String) = (this?.sessions as? SessionScope.Sessions)?.ids == listOf(id) && this.filter == null
private fun Scope?.isAll() = this?.sessions == SessionScope.All && this.filter == null

/**
 * L-2: the sidebar content. The current session (only while recording), "All sessions", the history by day, and
 * Settings pinned at the bottom as a 40 dp row with an icon. The selected row is `--fill2`. Phone: the list starts
 * below the button row (`--top-zone`) with 8 dp sides; [tablet] (T-6, inside the floating panel): items inset
 * `--panel-pad` with radius 22 (G-13a).
 */
@Composable
fun SessionsList(
    state: SessionsListState,
    onSelect: (Scope) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    tablet: Boolean = false,
    zone: TimeZone = TimeZone.getDefault(),
    bottomInset: Dp = 0.dp,
) {
    val c = CluetoothTheme.colors
    val radius = if (tablet) Concentric.panelRow else Css.Item.borderRadius
    val listPad = if (tablet) Css.PhoneTablet_SideList.padding else Css.SideList.padding
    val footPad = Css.PhoneTablet_SideFoot.padding // `.side-foot` 8 dp (+ the navigation bar on phone)
    Column(modifier.fillMaxHeight().background(c.panel)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("sessionsList"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = listPad.left, top = listPad.top, end = listPad.right,
            ),
        ) {
            state.current?.let { cur ->
                item(key = "current") {
                    ListItem(
                        Strings.CurrentSession,
                        sub = Strings.startedAt(Format.time(cur.startedMs, zone), cur.deviceCount),
                        selected = state.selected.isSession(cur.id),
                        radius = radius,
                        onClick = { onSelect(Scope.session(cur.id)) },
                    )
                }
            }
            if (state.all.sessionCount > 0) {
                item(key = "all") {
                    ListItem(
                        Strings.AllSessions,
                        sub = Strings.allSessionsSub(state.all.sessionCount, state.all.deviceCount),
                        selected = state.selected.isAll(),
                        radius = radius,
                        onClick = { onSelect(Scope.All) },
                    )
                }
            }
            for (day in state.days) {
                item(key = "day-${day.label}") { SectionLabel(day.label) }
                items(day.sessions, key = { it.id }) { s ->
                    ListItem(
                        Format.time(s.startedMs, zone),
                        sub = Format.sessionSummary(s.activeMs, s.distanceM, s.deviceCount),
                        selected = state.selected.isSession(s.id),
                        radius = radius,
                        onClick = { onSelect(Scope.session(s.id)) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = footPad.left, top = footPad.top, end = footPad.right, bottom = footPad.bottom + bottomInset)) {
            NavRow(Strings.Settings, onSettings, radius)
        }
    }
}

/** `.nav`: a 40 dp row with a `--t2` icon, 10 dp padding and gap, pressed `--fill2`. */
@Composable
private fun NavRow(label: String, onClick: () -> Unit, radius: Dp) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    Row(
        Modifier
            .fillMaxWidth()
            .height(Css.Nav.height)
            .clip(shape)
            .background(pressedFill(source, false))
            .plainClickable(source, role = Role.Button, onClick = onClick)
            .padding(horizontal = Css.Nav.padding.left),
        horizontalArrangement = Arrangement.spacedBy(Css.Nav.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.settings, null, tint = CluetoothTheme.colors.t2)
        Text(label)
    }
}

/**
 * L-1: the phone sidebar. A full-height `--panel` panel 82% of the screen wide, flush, no radius, no shadow. The
 * whole main screen slides right by the same width (`--t-std`); the visible map strip is dimmed with `--dim-strip`
 * and a tap on it closes the sidebar. [main] is the whole main screen (map, sheets, recorder); the menu button is
 * drawn by [MenuButton] above both.
 */
@Composable
fun PhoneSidebarLayout(
    open: Boolean,
    onClose: () -> Unit,
    sidebar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    main: @Composable () -> Unit,
) {
    val m = CluetoothTheme.motion
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideW = Tokens.sideW(CssEnv(containerWidth = maxWidth.value))
        val shift by animateDpAsState(if (open) sideW else 0.dp, m.std(), label = "sideShift")
        val dim by animateFloatAsState(if (open) 1f else 0f, m.std(), label = "sideDim")
        Box(Modifier.fillMaxSize().offset { IntOffset(shift.roundToPx(), 0) }) {
            main()
            if (dim > 0f) {
                Box(
                    Modifier.fillMaxSize().alpha(dim).background(CluetoothTheme.colors.dimStrip).testTag("sidebarDim")
                        .clickable(remember { MutableInteractionSource() }, null, enabled = open, onClick = onClose),
                )
            }
        }
        if (shift > 0.dp) {
            Box(Modifier.width(sideW).fillMaxHeight().offset { IntOffset((shift - sideW).roundToPx(), 0) }) {
                sidebar(Modifier.fillMaxSize())
            }
        }
    }
}
