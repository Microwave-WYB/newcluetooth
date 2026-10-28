package edu.ucsd.sysnet.cluetoothscanner.ui.v3.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab

/**
 * The stock [TopAppBar] every page uses: title, an optional one-line subtitle under it, actions.
 * While the shell's floating record pill shows, the actions end [LocalRecordPillInset] early so the
 * pill never covers them; the title keeps the rest of the width.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** A second line under the subtitle, e.g. a scanner problem; shown in the error color. */
    notice: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    /** Long press on the subtitle, e.g. to copy the coordinates in it. */
    onSubtitleLongClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        modifier = if (onSubtitleLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onSubtitleLongClick) else Modifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (notice != null) {
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        modifier = modifier,
        navigationIcon = { navigation?.invoke() },
        actions = {
            actions()
            Spacer(Modifier.width(LocalRecordPillInset.current))
        },
    )
}

@Composable
fun TopBarIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    IconButton(onClick = onClick, modifier = modifier) { Icon(icon, description, tint = tint) }
}

@Composable
fun BackButton(onClick: () -> Unit = {}) =
    TopBarIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onClick)

@Composable
fun SearchButton(onClick: () -> Unit = {}) = TopBarIconButton(Icons.Filled.Search, stringResource(R.string.action_search), onClick)

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.Scan -> Icons.AutoMirrored.Outlined.BluetoothSearching
    AppTab.Nearby -> Icons.Outlined.Place
    AppTab.History -> Icons.Outlined.History
}

/** The stock [NavigationBar] with the three tabs. */
@Composable
fun V3NavigationBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tabIcon(tab), null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}
