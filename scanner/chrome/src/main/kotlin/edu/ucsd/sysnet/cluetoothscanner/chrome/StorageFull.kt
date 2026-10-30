package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Button
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ButtonKind
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ListItem
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Switch
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.plainClickable
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/** R-10: the two cleanups, in the dialog and in Settings › Storage (O-1 item 11). */
enum class Cleanup { UploadedRaw, LocalDatabase }

/** R-10: what each cleanup frees, as the core reports it. */
data class Reclaimable(val uploadedRawBytes: Long, val localDatabaseBytes: Long) {
    fun of(c: Cleanup): Long = when (c) {
        Cleanup.UploadedRaw -> uploadedRawBytes
        Cleanup.LocalDatabase -> localDatabaseBytes
    }
}

/** R-10: a cleanup that failed, with the core's reason. */
data class CleanupFailure(val cleanup: Cleanup, val reason: String)

/** "Couldn't delete uploaded raw files: …". */
fun cleanupFailedText(f: CleanupFailure): String = when (f.cleanup) {
    Cleanup.UploadedRaw -> Strings.deleteUploadedRawFailed(f.reason)
    Cleanup.LocalDatabase -> Strings.clearLocalDatabaseFailed(f.reason)
}

/** The switch or button label of a cleanup. */
fun cleanupTitle(c: Cleanup): String = when (c) {
    Cleanup.UploadedRaw -> Strings.DeleteUploadedRaw
    Cleanup.LocalDatabase -> Strings.ClearLocalDatabase
}

/** R-10: the storage-full dialog as :app's reducer describes it. */
data class StorageFullUi(
    val freeBytes: Long,
    /** null while the core is asked ([checking]), or when it cannot say (no size shows). */
    val reclaimable: Reclaimable?,
    /** The switches (both off when the dialog opens). */
    val chosen: Set<Cleanup>,
    /** A cleanup is running: the switches and the button wait. */
    val freeing: Boolean,
    /** After "Free up space": what the cleanups that succeeded freed (null when none did). */
    val freedBytes: Long?,
    /** Free space is back above the floor: "Resume" continues the recording. */
    val canResume: Boolean,
    /** The core is being asked what each cleanup frees. */
    val checking: Boolean = false,
    /** After "Free up space": each cleanup that failed (its switch stays on), shown next to what the others freed. */
    val failures: List<CleanupFailure> = emptyList(),
    /** The free space and sizes couldn't be read, with the core's reason; [freeBytes] is then the last one read. */
    val readFailure: String? = null,
)

class StorageFullActions(
    val onChoose: (Cleanup, Boolean) -> Unit,
    val onFreeUp: () -> Unit,
    val onNotNow: () -> Unit,
    val onResume: () -> Unit,
)

/**
 * R-10: over everything, on the `--scrim` (it takes the taps; Back is "Not now"). A `--panel` card,
 * `min(--panel-w, width − 2 × --edge)` wide, radius and shadow as the location popover: the title, the body, the
 * free space, the two switches with what each frees, what the last cleanups freed and why any failed (in `--rec`),
 * then "Not now" and "Free up
 * space" (enabled with a switch on), or "Resume" once free space is back above the floor.
 */
@Composable
fun StorageFullDialog(ui: StorageFullUi, actions: StorageFullActions, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    val shape = RoundedCornerShape(Concentric.popover)
    BoxWithConstraints(
        modifier.fillMaxSize().background(c.scrim).plainClickable(remember { MutableInteractionSource() }, role = null) {}.testTag("storageFull"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(minOf(Tokens.panelW, maxWidth - Tokens.edge * 2))
                .tokenShadow(CluetoothTheme.shadows.shadowRaised, shape)
                .clip(shape)
                .background(c.panel)
                .padding(Tokens.gutter),
            verticalArrangement = Arrangement.spacedBy(Css.Btn.gap),
        ) {
            Text(Strings.StorageFullTitle, style = t.title)
            Text(Strings.StorageFullBody, style = t.sub, color = c.t2)
            Text(Strings.freeSpace(ui.freeBytes), style = t.sub, color = c.t2, modifier = Modifier.testTag("freeSpace"))
            for (cleanup in Cleanup.entries) {
                val on = cleanup in ui.chosen
                ListItem(
                    cleanupTitle(cleanup), sub = ui.reclaimable?.let { Strings.frees(it.of(cleanup)) } ?: Strings.Checking.takeIf { ui.checking },
                    horizontalPadding = Dp(0f),
                    trailing = {
                        Switch(on, { if (!ui.freeing) actions.onChoose(cleanup, it) }, Modifier.testTag("switch:$cleanup"), label = cleanupTitle(cleanup))
                    },
                )
            }
            if (ui.freeing) Text(Strings.Freeing, style = t.sub, color = c.t2)
            else {
                ui.freedBytes?.let { Text(Strings.freed(it), style = t.sub, modifier = Modifier.testTag("freed")) }
                for (f in ui.failures) Text(cleanupFailedText(f), style = t.sub, color = c.rec, modifier = Modifier.testTag("failed:${f.cleanup}"))
                ui.readFailure?.let { Text(Strings.reclaimableFailed(it), style = t.sub, color = c.rec, modifier = Modifier.testTag("readFailed")) }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Css.BtnRow.gap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(Strings.NotNow, actions.onNotNow, Modifier.testTag("notNow"))
                if (ui.canResume) Button(Strings.Resume, actions.onResume, Modifier.testTag("resume"), kind = ButtonKind.Primary, small = true)
                else Button(
                    Strings.FreeUpSpace, actions.onFreeUp, Modifier.testTag("freeUp"), kind = ButtonKind.Primary, small = true,
                    enabled = ui.chosen.isNotEmpty() && !ui.freeing,
                )
            }
        }
    }
}
