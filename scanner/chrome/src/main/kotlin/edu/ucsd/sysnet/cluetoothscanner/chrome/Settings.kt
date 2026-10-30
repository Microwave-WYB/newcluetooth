package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Checkbox
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.FloatButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ListItem
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SectionLabel
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Segmented
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Switch
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** docs/v2-api.md §5.4. */
data class SettingsUi(val settings: Settings, val uploads: UploadStatus, val storage: StorageUi)

/** O-1 items 8–11; [mapCacheBytes] null until :map reports it. */
data class StorageUi(
    val databaseBytes: Long,
    val rawFilesBytes: Long,
    val mapCacheBytes: Long?,
    val pendingCount: Int,
    val exportScope: Scope,
    val exportFormat: ExportFormat?,
    val exporting: Boolean,
    val deleteConfirm: DeleteConfirm?,
    /** O-1 item 11's own scope, independent of Export's; it starts like [exportScope]. */
    val deleteScope: Scope = exportScope,
    /** R-10 in O-1 item 11: what each cleanup frees (null until the core has said; no size shows then). */
    val reclaimable: Reclaimable? = null,
    /** The cleanup waiting for its confirmation. */
    val cleanupConfirm: Cleanup? = null,
    /** A cleanup is running. */
    val cleaning: Boolean = false,
    /** What the last cleanup freed (null when it failed or none ran). */
    val cleanupFreed: Long? = null,
    /** Why the last cleanup failed. */
    val cleanupFailure: CleanupFailure? = null,
    /** Why the cleanups' sizes couldn't be read (none show then). */
    val reclaimFailure: String? = null,
)

/** Confirm: the scope's deletion, keeping un-uploaded data; LoseUnuploaded: the explicit second step. */
enum class DeleteConfirm { Confirm, LoseUnuploaded }

/** The Settings page's callbacks; the storage ones drive :app's flow over `CoreStorage` and `ExportShare` (B). */
class SettingsActions(
    val onBack: () -> Unit,
    val onSettings: ((Settings) -> Settings) -> Unit,
    val onUploadNow: () -> Unit,
    val onExportScope: (Scope) -> Unit,
    val onExportFormat: (ExportFormat) -> Unit,
    val onShare: () -> Unit,
    val onSaveToDownloads: () -> Unit,
    /** Delete's scope choice; it never changes Export's ([onExportScope]). */
    val onDeleteScope: (Scope) -> Unit,
    /** Delete: the user tapped Delete (→ [DeleteConfirm.Confirm]). */
    val onDeleteRequest: () -> Unit,
    /** The confirmation's Delete: :app moves to [DeleteConfirm.LoseUnuploaded] when files are pending, else deletes. */
    val onDeleteConfirm: () -> Unit,
    /** Second step: false keeps un-uploaded data until it has uploaded (the default, §2.8), true loses it. */
    val onDelete: (includeUnuploaded: Boolean) -> Unit,
    val onDeleteCancel: () -> Unit,
    /** R-10: the user tapped a cleanup's button (→ its confirmation). */
    val onCleanupRequest: (Cleanup) -> Unit = {},
    val onCleanupConfirm: () -> Unit = {},
    val onCleanupCancel: () -> Unit = {},
)

/**
 * O-1: the Settings page. It slides in from the right (`--t-page`) over a `--bg` page with a `--top-zone` strip that
 * holds the back FloatButton and "Settings" (17/650). The rows are O-1 items 1–12 in order, with the Storage group
 * (8–11): usage with its breakdown, Export (scope, format, share or Downloads) and Delete (scope, confirmation, and
 * the explicit second step to lose un-uploaded data).
 *
 * @param sessionLabel the shown session's label for the one-session scope ("6:05 PM"), or null when only All applies
 * @param sessionScope the shown session's scope
 */
@Composable
fun SettingsPage(
    open: Boolean,
    ui: SettingsUi,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    sessionLabel: String? = null,
    sessionScope: Scope? = null,
    statusInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    val c = CluetoothTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val x by animateDpAsState(if (open) 0.dp else maxWidth, CluetoothTheme.motion.page(), label = "settingsX")
        if (x >= maxWidth) return@BoxWithConstraints
        Box(Modifier.fillMaxSize().offset { IntOffset(x.roundToPx(), 0) }.background(c.bg).testTag("settingsPage")) {
            Column(
                Modifier.fillMaxSize().padding(top = statusInset + Css.PageBar.height).verticalScroll(rememberScrollState())
                    .padding(bottom = Tokens.gutter + bottomInset),
            ) { SettingsRows(ui, actions, sessionLabel, sessionScope) }
            Row(
                Modifier.fillMaxWidth().background(c.bg).padding(top = statusInset).height(Css.PageBar.height)
                    .padding(horizontal = Css.PageBar.padding.left),
                horizontalArrangement = Arrangement.spacedBy(Css.PageBar.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatButton(Glyphs.back, Strings.Back, actions.onBack)
                Text(Strings.Settings, style = CluetoothTheme.type.title)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListItem(title, sub = sub, trailing = { Switch(checked, onChange, label = title) })

@Composable
private fun SettingsRows(ui: SettingsUi, a: SettingsActions, sessionLabel: String?, sessionScope: Scope?) {
    val s = ui.settings
    val st = ui.storage
    // 1–4
    ListItem(Strings.UserId, end = s.userId ?: "")
    SwitchRow(Strings.RecordOnOpen, Strings.RecordOnOpenSub, s.recordOnOpen) { v -> a.onSettings { it.copy(recordOnOpen = v) } }
    SwitchRow(Strings.WifiOnly, null, s.wifiOnlyUpload) { v -> a.onSettings { it.copy(wifiOnlyUpload = v) } }
    SwitchRow(Strings.CodedPhy, Strings.CodedPhySub, s.scanCodedPhy) { v -> a.onSettings { it.copy(scanCodedPhy = v) } }
    // 5
    val pending = if (ui.uploads.pendingCount == 0) Strings.NothingPending
    else Strings.pendingSub(ui.uploads.pendingCount, Format.bytes(ui.uploads.pendingBytes))
    ListItem(
        Strings.PendingUploads,
        sub = if (ui.uploads.failedCount > 0) "$pending · ${Strings.uploadFailed(ui.uploads.failedCount)}" else pending,
        trailing = { Button(Strings.UploadNow, a.onUploadNow, kind = ButtonKind.Tonal, small = true, enabled = ui.uploads.pendingCount > 0) },
    )
    // 6–7
    SectionLabel(Strings.Filters)
    SwitchRow(Strings.HideUnnamedApple, Strings.HideUnnamedAppleSub, s.hideUnnamedApple) { v -> a.onSettings { it.copy(hideUnnamedApple = v) } }
    // 8–11
    SectionLabel(Strings.Storage)
    val total = st.databaseBytes + st.rawFilesBytes + (st.mapCacheBytes ?: 0)
    ListItem(
        Strings.StorageUsed,
        sub = Strings.storageBreakdown(Format.bytes(st.databaseBytes), Format.bytes(st.rawFilesBytes), st.mapCacheBytes?.let(Format::bytes)),
        end = Format.bytes(total),
    )
    ExportBlock(st, a, sessionLabel, sessionScope)
    DeleteBlock(st, a, sessionLabel, sessionScope)
    // 12
    ListItem(Strings.AppVersion, end = s.appVersion)
}

/** The scope choice of Export and Delete: the shown session or All sessions (single selection, no multi-select). */
@Composable
private fun ScopeChoice(scope: Scope, onScope: (Scope) -> Unit, sessionLabel: String?, sessionScope: Scope?) {
    if (sessionLabel == null || sessionScope == null) return
    val options = listOf(sessionLabel, Strings.AllSessions)
    Box(Modifier.padding(horizontal = Tokens.gutter, vertical = Css.Seg.padding.top * 2)) {
        Segmented(options, if (scope.sessions == SessionScope.All) 1 else 0, { onScope(if (it == 1) Scope.All else sessionScope) }, Modifier.fillMaxWidth())
    }
}

private fun scopeName(scope: Scope, sessionLabel: String?) =
    if (scope.sessions == SessionScope.All || sessionLabel == null) Strings.AllSessions else sessionLabel

@Composable
private fun ExportBlock(st: StorageUi, a: SettingsActions, sessionLabel: String?, sessionScope: Scope?) {
    ListItem(Strings.ExportData, sub = Strings.ExportSub)
    ScopeChoice(st.exportScope, a.onExportScope, sessionLabel, sessionScope)
    val formats = listOf(
        Triple(ExportFormat.ParquetRaw, Strings.FormatParquet, Strings.FormatParquetSub),
        Triple(ExportFormat.Csv, Strings.FormatCsv, Strings.FormatCsvSub),
        Triple(ExportFormat.SqliteSnapshot, Strings.FormatSqlite, Strings.FormatSqliteSub),
    )
    for ((f, title, sub) in formats) {
        ListItem(title, sub = sub, selected = st.exportFormat == f, onClick = { a.onExportFormat(f) }, trailing = { Checkbox(st.exportFormat == f) })
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.gutter, vertical = Css.Item.padding.top),
        horizontalArrangement = Arrangement.spacedBy(Css.BtnRow.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ready = st.exportFormat != null && !st.exporting
        Button(Strings.Share, a.onShare, kind = ButtonKind.Primary, small = true, enabled = ready)
        Button(Strings.SaveToDownloads, a.onSaveToDownloads, kind = ButtonKind.Tonal, small = true, enabled = ready)
        if (st.exporting) Text(Strings.Preparing, style = CluetoothTheme.type.sub, color = CluetoothTheme.colors.t2)
    }
}

/**
 * O-1 item 11 and §2.8: choose the scope, then Delete; a confirmation that keeps un-uploaded data; and only when
 * files are pending, the explicit second step that offers losing them.
 */
@Composable
private fun DeleteBlock(st: StorageUi, a: SettingsActions, sessionLabel: String?, sessionScope: Scope?) {
    val c = CluetoothTheme.colors
    ListItem(Strings.DeleteLocalData, sub = Strings.DeleteSub)
    ScopeChoice(st.deleteScope, a.onDeleteScope, sessionLabel, sessionScope)
    when (st.deleteConfirm) {
        null -> Box(Modifier.padding(horizontal = Tokens.gutter, vertical = Css.Item.padding.top)) {
            Button(Strings.Delete, a.onDeleteRequest, kind = ButtonKind.Tonal, small = true)
        }
        DeleteConfirm.Confirm -> ConfirmCard(
            Strings.deleteConfirmTitle(scopeName(st.deleteScope, sessionLabel)), Strings.DeleteConfirmBody,
            secondary = Strings.Cancel to a.onDeleteCancel, primary = Strings.Delete to a.onDeleteConfirm,
        )
        DeleteConfirm.LoseUnuploaded -> ConfirmCard(
            Strings.deleteLoseTitle(st.pendingCount), Strings.DeleteLoseBody,
            // §2.8: keeping un-uploaded data is the primary choice; losing it is the secondary one
            secondary = Strings.DeleteNow to { a.onDelete(true) }, primary = Strings.KeepUntilUploaded to { a.onDelete(false) },
        )
    }
    CleanupBlock(st, a)
}

/**
 * O-1 item 11 with R-10: "Delete uploaded raw files" and "Clear local database", each with the space it frees, a
 * confirmation, and what it freed.
 */
@Composable
private fun CleanupBlock(st: StorageUi, a: SettingsActions) {
    for (cleanup in Cleanup.entries) {
        ListItem(
            cleanupTitle(cleanup), sub = st.reclaimable?.let { Strings.frees(it.of(cleanup)) },
            trailing = {
                Button(
                    cleanupAction(cleanup), { a.onCleanupRequest(cleanup) }, Modifier.testTag("cleanup:$cleanup"), kind = ButtonKind.Tonal,
                    small = true, enabled = st.cleanupConfirm == null && !st.cleaning,
                )
            },
        )
    }
    st.cleanupConfirm?.let { cleanup ->
        val (title, body) = when (cleanup) {
            Cleanup.UploadedRaw -> Strings.DeleteUploadedRawConfirmTitle to Strings.DeleteUploadedRawConfirmBody
            Cleanup.LocalDatabase -> Strings.ClearLocalDatabaseConfirmTitle to Strings.ClearLocalDatabaseConfirmBody
        }
        ConfirmCard(title, body, secondary = Strings.Cancel to a.onCleanupCancel, primary = cleanupAction(cleanup) to a.onCleanupConfirm, tag = "cleanupConfirm")
    }
    if (st.cleaning) ListItem(Strings.Freeing)
    if (!st.cleaning) {
        st.cleanupFreed?.let { ListItem(Strings.freed(it), modifier = Modifier.testTag("cleanupFreed")) }
        st.cleanupFailure?.let { ListItem(cleanupFailedText(it), titleColor = CluetoothTheme.colors.rec, modifier = Modifier.testTag("cleanupFailed")) }
    }
    st.reclaimFailure?.let { ListItem(Strings.reclaimableFailed(it), titleColor = CluetoothTheme.colors.rec, modifier = Modifier.testTag("reclaimFailed")) }
}

private fun cleanupAction(c: Cleanup): String = when (c) {
    Cleanup.UploadedRaw -> Strings.Delete
    Cleanup.LocalDatabase -> Strings.Clear
}

/** An inline confirmation on `--fill2` (no dialog, no shadow inside the page, G-5). */
@Composable
private fun ConfirmCard(
    title: String,
    body: String,
    secondary: Pair<String, () -> Unit>,
    primary: Pair<String, () -> Unit>,
    tag: String = "deleteConfirm",
) {
    val c = CluetoothTheme.colors
    Column(
        Modifier.padding(horizontal = Tokens.gutter, vertical = Css.Item.padding.top).fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.rItem)).background(c.fill2).padding(Tokens.gutter).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(Css.Btn.gap),
    ) {
        Text(title, style = CluetoothTheme.type.title)
        Text(body, style = CluetoothTheme.type.sub, color = c.t2)
        Row(horizontalArrangement = Arrangement.spacedBy(Css.BtnRow.gap), verticalAlignment = Alignment.CenterVertically) {
            TextButton(secondary.first, secondary.second, Modifier.testTag("secondary:${secondary.first}"))
            Button(primary.first, primary.second, Modifier.testTag("primary:${primary.first}"), kind = ButtonKind.Primary, small = true)
        }
    }
}
