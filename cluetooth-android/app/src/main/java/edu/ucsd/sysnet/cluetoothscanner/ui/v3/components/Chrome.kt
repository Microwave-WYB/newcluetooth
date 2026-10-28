package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab

/**
 * Page title, optional status line and trailing actions. The record button's mini form
 * is passed in as the last action by the shell.
 */
@Composable
fun V3TopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(start = if (navigation == null) 20.dp else 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            status?.invoke()
        }
        actions()
    }
}

@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(dot))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun TopBarIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun BackButton(onClick: () -> Unit = {}) =
    TopBarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick)

@Composable
fun SearchButton(onClick: () -> Unit = {}) = TopBarIconButton(Icons.Filled.Search, "搜索", onClick)

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.Scan -> Icons.AutoMirrored.Outlined.BluetoothSearching
    AppTab.Nearby -> Icons.Outlined.Place
    AppTab.Recordings -> Icons.Outlined.RadioButtonChecked
}

/** Bottom navigation with three tabs; a quiet bar with an indicator pill, no elevation. */
@Composable
fun V3BottomNav(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            AppTab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier.weight(1f).height(64.dp).clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.height(28.dp).width(56.dp).clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            tabIcon(tab), null,
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** The surface of a bottom sheet: rounded top, drag handle, hairline instead of a heavy shadow. */
@Composable
fun SheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            }
            content()
        }
    }
}

/** Dim layer behind a sheet. */
@Composable
fun BoxScope.Scrim(alpha: Float = 0.32f) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = alpha)))
}

/** A dimmed layer with a bottom-aligned sheet on top; tapping the dim area dismisses. */
@Composable
fun BoxScope.SheetLayer(onDismiss: () -> Unit = {}, sheet: @Composable () -> Unit) {
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    )
    Box(Modifier.align(Alignment.BottomCenter)) { sheet() }
}
