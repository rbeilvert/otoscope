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

            var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions()) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result -> permissionsGranted = result.values.all { it } }

            val enableBtLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) vm.startScan()
            }

            // The Wi-Fi settings panel doesn't report a result code, so just
            // re-scan when the user returns. startScan() re-checks Wi-Fi state.
            val enableWifiLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { vm.startScan() }

            OtoscopeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OtoscopeScreen(
                        state = state,
                        adverts = adverts,
                        permissionsGranted = permissionsGranted,
                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                        onEnableBluetooth = {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onEnableWifi = {
                            enableWifiLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
                        },
                        onStartScan = vm::startScan,
                        onStopScan = vm::stopScan,
                        onConnect = vm::connect,
                        onDisconnect = vm::disconnect,
                        onSetFlip = vm::setFlipEnabled,
                    )
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
