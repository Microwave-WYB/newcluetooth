package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText
import kotlinx.coroutines.delay

// A pushed sheet's body while its detail is not Found: never blank. Pending shows a loading line after a short delay
// (a fast read doesn't flash it), Missing says what is missing, Failed keeps its message.

internal object LoadTags {
    const val PENDING = "load-pending"
    const val MISSING = "load-missing"
    const val FAILED = "load-failed"
}

internal object LoadTexts {
    const val Loading = "Loading…"
    const val DeviceMissing = "Device not found in this session"
    const val ProductMissing = "Product not found in this session"
}

/** How long a read may take before the loading line shows. */
internal const val PENDING_DELAY_MS = 300L

/** Pending: nothing for [PENDING_DELAY_MS], then the loading line. */
@Composable
internal fun LoadPending() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PENDING_DELAY_MS)
        shown = true
    }
    if (shown) EmptyText(LoadTexts.Loading, Modifier.testTag(LoadTags.PENDING))
}

@Composable
internal fun LoadMissing(text: String) = EmptyText(text, Modifier.testTag(LoadTags.MISSING))

@Composable
internal fun LoadFailed(message: String) = EmptyText(message, Modifier.testTag(LoadTags.FAILED))
