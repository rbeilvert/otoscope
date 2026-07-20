package dev.rubec.otoscope.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import dev.rubec.otoscope.debug.FileLog
import dev.rubec.otoscope.vendor.CameraVendors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class CameraBleScanner(context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    val isBluetoothEnabled: Boolean
        get() = manager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun scan(): Flow<CameraAdvert> = callbackFlow {
        val scanner = manager?.adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth LE scanner unavailable")

        // Track which BSSIDs we've already logged this scan run to avoid spamming logs.
        val loggedBssids = HashSet<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val deviceName = device.name ?: result.scanRecord?.deviceName
                val scanRecord = result.scanRecord?.bytes
                val advert = CameraVendors.parseAdvert(
                    bleAddress = device.address,
                    deviceName = deviceName,
                    scanRecord = scanRecord,
                    rssi = result.rssi,
                )
                if (advert != null) {
                    // First sighting of this device: dump the raw envelope so
                    // we can confirm the vendor guess and payload after the fact.
                    // Useful when a user's hardware pairs but doesn't stream.
                    if (loggedBssids.add(advert.bssid)) {
                        FileLog.i(
                            TAG,
                            "match ${advert.vendor.displayName}: " +
                                "name=$deviceName addr=${device.address} bssid=${advert.bssid} " +
                                "rssi=${result.rssi} raw=${scanRecord?.hex()}",
                        )
                    }
                    trySend(advert)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        scanner.startScan(callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "BleScan"
    }
}
