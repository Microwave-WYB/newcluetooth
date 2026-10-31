package edu.ucsd.sysnet.cluetoothscanner.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * A failure the user must see ([Notice]): a small raised banner built from :designsystem's pieces, with the error's
 * words, Retry when the notice can retry, and a dismiss.
 */
@Composable
internal fun NoticeBanner(notice: Notice, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Tokens.rItem)
    Row(
        modifier
            .padding(Tokens.edge)
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .tokenShadow(CluetoothTheme.shadows.shadowRaised, shape)
            .clip(shape)
            .background(CluetoothTheme.colors.panel)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.message, Modifier.weight(1f), color = CluetoothTheme.colors.warn, maxLines = 3)
        if (notice.retry != null) TextButton(notice.retryLabel ?: Strings.TryAgain, onRetry, Modifier.testTag("noticeRetry"))
        TextButton(DISMISS, onDismiss, Modifier.testTag("noticeDismiss"))
    }
}

private const val DISMISS = "Dismiss"
