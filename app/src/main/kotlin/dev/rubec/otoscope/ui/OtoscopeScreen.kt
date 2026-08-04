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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rubec.otoscope.BuildConfig
import dev.rubec.otoscope.ble.CameraAdvert
import dev.rubec.otoscope.stream.BatteryStatus
import dev.rubec.otoscope.vendor.CameraVendors
import dev.rubec.otoscope.vendor.DiscoveryMode
import dev.rubec.otoscope.vm.CameraState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtoscopeScreen(
    state: CameraState,
    adverts: List<CameraAdvert>,
    onEnableBluetooth: () -> Unit,
    onEnableWifi: () -> Unit,
    onStartScan: (DiscoveryMode) -> Unit,
    onStopScan: () -> Unit,
    onConnect: (CameraAdvert) -> Unit,
    onDisconnect: () -> Unit,
    onSetFlip: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Otoscope") },
                actions = {
                    DeviceActions(
                        state = state,
                        onEnableBluetooth = onEnableBluetooth,
                        onEnableWifi = onEnableWifi,
                        onStopScan = onStopScan,
                        onDisconnect = onDisconnect,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is CameraState.Streaming -> StreamingView(state, onDisconnect, onSetFlip)
                is CameraState.Scanning,
                is CameraState.Found,
                is CameraState.Connecting -> ScanView(
                    state = state,
                    adverts = adverts,
                    onConnect = onConnect,
                    onEnableWifi = onEnableWifi,
                    padding = PaddingValues(16.dp),
                )
                // Everything else (Idle, Error, Disconnected, BluetoothOff,
                // WifiOff) lands on the home screen. The picker stays visible
                // so the user can pick the other discovery path even if the
                // required radio for one of them is off.
                else -> HomeView(
                    state = state,
                    onStartScan = onStartScan,
                    padding = PaddingValues(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceActions(
    state: CameraState,
    onEnableBluetooth: () -> Unit,
    onEnableWifi: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // The top-bar action set is now purely context-sensitive: disconnect while
    // streaming, stop while scanning, or "Turn on Bluetooth / Wi-Fi" if the
    // corresponding radio is off. The "start scan" affordance moved into the
    // home-screen model picker, so there's no explicit Scan button here anymore.
    when {
        state is CameraState.Streaming -> {
            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }
        state is CameraState.Scanning || state is CameraState.Found -> {
            OutlinedButton(onClick = onStopScan) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Stop")
            }
        }
        state is CameraState.BluetoothOff -> {
            Button(onClick = onEnableBluetooth) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Turn on Bluetooth")
            }
        }
        state is CameraState.WifiOff -> {
            Button(onClick = onEnableWifi) {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Turn on Wi-Fi")
            }
        }
    }
}

/**
 * Landing screen. User picks how to find their camera. Two paths:
 *  - **Bluetooth pairing** for BLE-advertising models (Xylla, iTiMO,
 *    JEGOAT). BLE scan → parse advert → auto-join the camera Wi-Fi.
 *  - **Wi-Fi scan** for models without BLE (EarFairy). Wi-Fi scan filtered by
 *    SSID prefix → user taps to connect.
 *
 * The cards are always rendered. Runtime-permission gating and the enable-radio
 * flow happen after the user's tap (see [MainActivity]) so someone streaming
 * only from EarFairy is never asked for Bluetooth permission.
 *
 * Error / Disconnected banners still render here so they land the user back on
 * the picker with a chance to try again.
 */
@Composable
private fun HomeView(
    state: CameraState,
    onStartScan: (DiscoveryMode) -> Unit,
    padding: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        when (state) {
            is CameraState.Error -> ErrorHeader(state.message)
            is CameraState.Disconnected -> DisconnectedHeader(state.reason)
            is CameraState.BluetoothOff ->
                IconMessage(Icons.Default.BluetoothDisabled, "Bluetooth is off. Turn it on to pair a BLE camera.")
            is CameraState.WifiOff ->
                IconMessage(Icons.Default.WifiOff, "Wi-Fi is off. Turn it on to connect to a camera.")
            else -> Unit
        }
        if (state is CameraState.BluetoothOff || state is CameraState.WifiOff) Spacer(Modifier.height(16.dp))

        Text(
            "Pick the model of your otoscope",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))

        DiscoveryModeCard(
            models = CameraVendors.bleVendors.map { it.displayName },
            method = "Pair via Bluetooth",
            icon = Icons.Default.Bluetooth,
            onClick = { onStartScan(DiscoveryMode.BLE) },
        )
        Spacer(Modifier.height(12.dp))
        DiscoveryModeCard(
            models = CameraVendors.wifiScanVendors.map { it.displayName },
            method = "Connect via Wi-Fi",
            icon = Icons.Default.Wifi,
            onClick = { onStartScan(DiscoveryMode.WIFI_SCAN) },
        )
    }
}

/** Home-page card: model names listed one per line (so the user can see at
 *  a glance which card handles their otoscope), followed by the pairing
 *  method as a caption. */
@Composable
private fun DiscoveryModeCard(
    models: List<String>,
    method: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                for (model in models) {
                    Text(
                        model,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Text(
                method,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Scan-in-progress + results screen. Reached after the user tapped one of the
 *  home-screen model-picker cards. */
@Composable
private fun ScanView(
    state: CameraState,
    adverts: List<CameraAdvert>,
    onConnect: (CameraAdvert) -> Unit,
    onEnableWifi: () -> Unit,
    padding: PaddingValues,
) {
    val mode = when (state) {
        is CameraState.Scanning -> state.mode
        is CameraState.Found -> state.advert.vendor.discoveryMode
        is CameraState.Connecting -> state.advert.vendor.discoveryMode
        else -> null
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        when (state) {
            is CameraState.Connecting -> ConnectingHeader(state.advert.ssid, state.attempt, state.totalAttempts)
            else -> Unit
        }

        when (state) {
            is CameraState.BluetoothOff -> IconMessage(Icons.Default.BluetoothDisabled, "Bluetooth is off. Use the button above to turn it on.")
            is CameraState.WifiOff -> IconMessage(Icons.Default.WifiOff, "Wi-Fi is off. Use the button above to turn it on.")
            is CameraState.Scanning, is CameraState.Found -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text(
                    when (mode) {
                        DiscoveryMode.BLE -> "Scanning over Bluetooth..."
                        DiscoveryMode.WIFI_SCAN -> "Scanning nearby Wi-Fi networks..."
                        null -> "Scanning..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Unit
        }

        Spacer(Modifier.height(16.dp))

        if (adverts.isEmpty()) {
            Text(
                when (mode) {
                    DiscoveryMode.WIFI_SCAN ->
                        "Power on the otoscope. It will appear here once its Wi-Fi network shows up in scans."
                    else ->
                        "Power on the otoscope. It will appear here as soon as we pick up its BLE advertisement."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Wi-Fi-scan fallback. Some devices refuse to hand scan results to
            // apps (OEM Location Services gate, throttling, …), but Android
            // still shows the camera in system Wi-Fi settings. Give the user a
            // one-tap shortcut: join from settings, come back, and the current
            // connection is picked up automatically.
            if (mode == DiscoveryMode.WIFI_SCAN) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Can't see your camera?\nJoin it directly from Android's Wi-Fi settings:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onEnableWifi) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Open Wi-Fi settings")
                }
            }
        } else {
            Text(
                "Tap a camera to join its Wi-Fi. Android will ask you to confirm; " +
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
private fun IconMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(message)
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
private fun ConnectingHeader(ssid: String, attempt: Int, totalAttempts: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(12.dp))
        Column {
            Text("Joining $ssid…", style = MaterialTheme.typography.bodyMedium)
            if (totalAttempts > 1 && attempt > 1) {
                Text(
                    "Attempt $attempt of $totalAttempts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private fun DisconnectedHeader(reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PowerOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.size(12.dp))
        Text(reason, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun StreamingView(
    state: CameraState.Streaming,
    onDisconnect: () -> Unit,
    onSetFlip: (Boolean) -> Unit,
) {
    val frame by state.frame.collectAsStateWithLifecycle()
    val rotation by state.rotation.collectAsStateWithLifecycle()
    val model by state.model.collectAsStateWithLifecycle()
    val battery by state.battery.collectAsStateWithLifecycle()
    val diagnostics by state.diagnostics.collectAsStateWithLifecycle()
    val flipEnabled by state.flipEnabled.collectAsStateWithLifecycle()

    // Prevent the screen from sleeping while a stream is on-screen. Scoped to
    // the composable so it flips back off automatically when the user disconnects
    // or navigates away — no lifecycle callbacks or wake-lock permission needed.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CameraFrame(
            frame = frame,
            rotationDegrees = rotation,
            flipEnabled = flipEnabled,
            modifier = Modifier.fillMaxWidth()
        )

        CameraStatus(modelName = model, ssid = state.advert.ssid, battery = battery)

        // Ring-light control, shown only for vendors that expose it
        // (currently EarFairy). Local echo is instant; the camera doesn't
        // acknowledge but the LED responds within a video frame or two.
        state.session.led?.let { led ->
            val ledOn by led.enabled.collectAsStateWithLifecycle()
            LabeledSwitch(
                icon = Icons.Default.Lightbulb,
                title = "Ring light",
                subtitle = "Toggle the LEDs around the otoscope tip.",
                checked = ledOn,
                onCheckedChange = { led.setEnabled(it) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Mirror view",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Check to examine yourself, uncheck to examine someone else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = flipEnabled,
                onCheckedChange = onSetFlip,
            )
        }

        if (BuildConfig.DEBUG && diagnostics.isNotEmpty()) {
            DiagnosticsOverlay(diagnostics)
        }
    }
}

@Composable
private fun LabeledSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagnosticsOverlay(values: Map<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((key, value) in values) {
            Text(
                "$key: $value",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraStatus(
    modelName: String?,
    ssid: String,
    battery: BatteryStatus?,
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
private fun BatteryBadge(battery: BatteryStatus?) {
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
