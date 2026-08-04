package dev.rubec.otoscope

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rubec.otoscope.debug.FileLog
import dev.rubec.otoscope.ui.OtoscopeScreen
import dev.rubec.otoscope.ui.theme.OtoscopeTheme
import dev.rubec.otoscope.vendor.DiscoveryMode
import dev.rubec.otoscope.vm.CameraViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only. Collects a per-run log at
        // /sdcard/Android/data/dev.rubec.otoscope.debug/files/otoscope-debug.log
        if (BuildConfig.DEBUG) FileLog.init(applicationContext)
        setContent {
            val vm: CameraViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val adverts by vm.adverts.collectAsStateWithLifecycle()

            // Which discovery mode is waiting for a permission-request result.
            // Null when no request is pending. Only used to remember what to
            // do once the user grants or denies.
            var pendingMode: DiscoveryMode? by remember { mutableStateOf(null) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val mode = pendingMode
                pendingMode = null
                if (mode != null && result.values.all { it }) {
                    vm.startScan(mode)
                }
                // If some perms were denied we leave the app on the picker;
                // the user can tap again and Android will re-prompt or open
                // the settings page depending on the "don't ask again" state.
            }

            // Radio-enable launchers reset the ViewModel to Idle so the picker
            // is tappable again after the user turns on Bluetooth / Wi-Fi.
            // Simpler than remembering which discovery mode was in flight.
            val enableBtLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { vm.stopScan() }
            val enableWifiLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { vm.stopScan() }

            val onStartScan: (DiscoveryMode) -> Unit = { mode ->
                val needed = permissionsFor(mode)
                val missing = needed.any {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing) {
                    // Only ask for what THIS mode needs. An EarFairy-only user
                    // never sees a Bluetooth prompt; a BLE user never sees the
                    // Wi-Fi-scan prompt on API 33+.
                    pendingMode = mode
                    permissionLauncher.launch(needed)
                } else {
                    vm.startScan(mode)
                }
            }

            OtoscopeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OtoscopeScreen(
                        state = state,
                        adverts = adverts,
                        onEnableBluetooth = {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onEnableWifi = {
                            enableWifiLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
                        },
                        onStartScan = onStartScan,
                        onStopScan = vm::stopScan,
                        onConnect = vm::connect,
                        onDisconnect = vm::disconnect,
                        onSetFlip = vm::setFlipEnabled,
                    )
                }
            }
        }
    }

    /** Runtime permissions the given discovery mode needs at scan time.
     *  Kept minimal so a user only ever grants what their model requires. */
    private fun permissionsFor(mode: DiscoveryMode): Array<String> = when (mode) {
        DiscoveryMode.BLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        DiscoveryMode.WIFI_SCAN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // NEARBY_WIFI_DEVICES (neverForLocation) covers the scan API on
            // API 33+, but WifiInfo.getSSID() for the *current* connection
            // still returns "<unknown ssid>" without ACCESS_FINE_LOCATION on
            // many OEMs. Ask for both so both discovery paths (scan and
            // current-connection adoption) can name the network.
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // API 26..32: getScanResults() also requires ACCESS_FINE_LOCATION.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
