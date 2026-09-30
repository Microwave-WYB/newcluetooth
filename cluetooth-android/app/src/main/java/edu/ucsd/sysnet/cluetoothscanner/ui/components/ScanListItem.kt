package edu.ucsd.sysnet.cluetoothscanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import edu.ucsd.sysnet.cluetoothscanner.ui.DisplayItem
import edu.ucsd.sysnet.cluetoothscanner.ui.RssiColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanListItem(
    item: DisplayItem,
    onItemClick: (DisplayItem) -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier.clickable { onItemClick(item) },
        headlineContent = {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        },
        supportingContent = {
            Text(
                text = item.mac,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Text(
                text = if (item.rssi != null) "${item.rssi} dBm" else "N/A",
                style = MaterialTheme.typography.bodyMedium,
                color = getRssiColor(item.rssiColor, item.isStale),
                fontWeight = FontWeight.Medium
            )
        }
    )
}

@Composable
private fun getRssiColor(rssiColor: RssiColor, isStale: Boolean): Color {
    return if (isStale) {
        Color.Gray
    } else {
        when (rssiColor) {
            RssiColor.GREEN -> Color(0xFF4CAF50)  // Material Green
            RssiColor.YELLOW -> Color(0xFFFF9800) // Material Orange
            RssiColor.RED -> Color(0xFFF44336)    // Material Red
            RssiColor.GREY -> Color.Gray
        }
    }
}
