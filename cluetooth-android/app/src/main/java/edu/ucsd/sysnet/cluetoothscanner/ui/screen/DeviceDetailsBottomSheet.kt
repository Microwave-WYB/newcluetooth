package edu.ucsd.sysnet.cluetoothscanner.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.ble.BleParser
import edu.ucsd.sysnet.cluetoothscanner.ui.DisplayItem
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsBottomSheet(
    item: DisplayItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val packet = remember(item) { BleParser.parseAdvertisementData(item.record.raw) }
    var showTypeInfo by remember { mutableStateOf<UByte?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        SelectionContainer {
            Column {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = item.mac,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "General Information",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                InfoRow("RSSI", if (item.rssi != null) "${item.rssi} dBm" else "N/A")
                InfoRow("Timestamp", item.record.timestamp)
                InfoRow("Location", getLocationString(item.record))
                InfoRow("Accuracy", getAccuracyString(item.record))
                InfoRow("Local Address", if (isLocalAddress(item.mac)) "Yes" else "No")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Raw Data",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (packet.structures.isEmpty()) {
            SelectionContainer {
                Text(
                    text = "No advertisement data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Len",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(0.8f)
                )
                Text(
                    text = "Type",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1.2f)
                )
                Text(
                    text = "Data",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(3f)
                )
            }

            HorizontalDivider()

            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(packet.structures) { structure ->
                        PacketTableRow(
                            structure = structure,
                            onInfoClick = { showTypeInfo = structure.type }
                        )
                    }
                }
            }
        }
    }

    showTypeInfo?.let { type ->
        AlertDialog(
            onDismissRequest = { showTypeInfo = null },
            title = {
                Text("Advertisement Type")
            },
            text = {
                Text(BleParser.getAdTypeDescription(type))
            },
            confirmButton = {
                TextButton(onClick = { showTypeInfo = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PacketTableRow(
    structure: edu.ucsd.sysnet.cluetoothscanner.ble.AdStruct,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = structure.length.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.8f)
        )

        Row(
            modifier = Modifier.weight(1.2f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "0x${structure.type.toString(16).padStart(2, '0').uppercase()}",
                style = MaterialTheme.typography.bodySmall
            )

            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Type Info",
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Text(
            text = structure.data.joinToString("") { "%02x".format(it) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.weight(3f)
        )
    }
}

private fun getLocationString(record: edu.ucsd.sysnet.cluetoothscanner.data.BleRecord): String {
    return if (record.lat != null && record.lon != null) {
        "${record.lat}, ${record.lon}"
    } else {
        "Not available"
    }
}

private fun getAccuracyString(record: edu.ucsd.sysnet.cluetoothscanner.data.BleRecord): String {
    return if (record.accuracy != null) {
        "±${record.accuracy.toInt()}m"
    } else {
        "Not available"
    }
}

private fun isLocalAddress(mac: String): Boolean {
    // Check if the second character of the first octet is 2, 6, A, or E
    val firstOctet = mac.substring(0, 2)
    val secondChar = firstOctet[1]
    return secondChar in "26AEae"
}
