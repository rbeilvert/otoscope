package dev.rubec.otoscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.OtoscopeControlClient
import dev.rubec.otoscope.vm.CameraState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtoscopeScreen(
    state: CameraState,
    adverts: List<CameraAdvert>,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (CameraAdvert) -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Otoscope") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is CameraState.Streaming -> StreamingView(state, onDisconnect)
                else -> ScanView(
                    state = state,
                    adverts = adverts,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = onRequestPermissions,
                    onEnableBluetooth = onEnableBluetooth,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                    onConnect = onConnect,
                    padding = PaddingValues(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ScanView(
    state: CameraState,
    adverts: List<CameraAdvert>,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (CameraAdvert) -> Unit,
    padding: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        when (state) {
            is CameraState.Connecting -> ConnectingHeader(state.advert.ssid)
            is CameraState.Error -> ErrorHeader(state.message)
            else -> Unit
        }

        when {
            !permissionsGranted -> {
                Button(onClick = onRequestPermissions) {
                    Text("Grant Bluetooth permission")
                }
            }
            state is CameraState.BluetoothOff -> {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Bluetooth is off", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onEnableBluetooth) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Turn on Bluetooth")
                    }
                }
            }
            state is CameraState.WifiOff -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WifiOff, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Wi-Fi is off — turn it on to talk to the camera.")
                }
            }
            state is CameraState.Scanning || state is CameraState.Found -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onStopScan) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Stop scan")
                    }
                    Spacer(Modifier.size(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            else -> {
                Button(onClick = onStartScan) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan for camera")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (adverts.isEmpty()) {
            Text(
                "Power on the otoscope and hit Scan. It will appear here as soon as we " +
                    "pick up its BLE advertisement.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Tap a camera to join its Wi-Fi. Android will ask you to confirm — " +
                    "approve the prompt to connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(adverts, key = { it.bssid }) { advert ->
                    CameraCard(advert = advert, onClick = { onConnect(advert) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CameraCard(advert: CameraAdvert, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(advert.ssid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("BSSID: ${advert.bssid}", style = MaterialTheme.typography.bodySmall)
            Text("RSSI: ${advert.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConnectingHeader(ssid: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(12.dp))
        Text("Joining $ssid…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorHeader(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun StreamingView(state: CameraState.Streaming, onDisconnect: () -> Unit) {
    val frame by state.frame.collectAsStateWithLifecycle()
    val rotation by state.rotation.collectAsStateWithLifecycle()
    val model by state.model.collectAsStateWithLifecycle()
    val battery by state.battery.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CameraFrame(
            frame = frame,
            rotationDegrees = rotation,
            modifier = Modifier.fillMaxWidth()
        )

        CameraStatus(modelName = model, ssid = state.advert.ssid, battery = battery)

        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
    }
}

@Composable
private fun CameraStatus(
    modelName: String?,
    ssid: String,
    battery: OtoscopeControlClient.Battery?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                modelName ?: prettyModelFromSsid(ssid),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                ssid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BatteryBadge(battery)
    }
}

@Composable
private fun BatteryBadge(battery: OtoscopeControlClient.Battery?) {
    if (battery == null) return
    val pct = battery.percent.coerceIn(0, 100)
    val icon = when {
        battery.full -> Icons.Default.BatteryFull
        battery.charging -> Icons.Default.BatteryChargingFull
        pct >= 75 -> Icons.Default.BatteryFull
        pct >= 50 -> Icons.Default.Battery6Bar
        pct >= 25 -> Icons.Default.Battery4Bar
        pct > 0 -> Icons.Default.Battery2Bar
        else -> Icons.Default.PowerOff
    }
    val tint = when {
        battery.charging || battery.full -> MaterialTheme.colorScheme.primary
        pct < 20 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = "Battery", tint = tint)
        Spacer(Modifier.size(4.dp))
        Text(
            if (battery.full) "Full" else "$pct%",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

/** Friendly fallback when the camera hasn't replied to `getBoardInfo` yet —
 *  shows "Otoscope · 3B3D90" for an SSID like "Enjoy-3B3D90". */
private fun prettyModelFromSsid(ssid: String): String {
    val tail = ssid.substringAfter('-', missingDelimiterValue = "")
    return if (tail.isNotEmpty()) "Otoscope · $tail" else "Otoscope"
}
