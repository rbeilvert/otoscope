package dev.rubec.otoscope.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.rubec.otoscope.debug.FileLog as Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * One-shot BLE GATT read used as a "knock" by vendors that need a GATT
 * round-trip before the camera enables its WiFi AP (e.g. JEGOAT reads
 * service `0x9900` / characteristic `0x0099` to trigger broadcast; the
 * returned value is irrelevant, the side-effect is what matters).
 *
 * Connects → discovers services → reads → disconnects. Returns the read
 * bytes (or null if anything failed) within [timeoutMs]. Suspends on
 * the GATT callbacks; safe to call from a coroutine.
 */
internal object BleKnock {
    private const val TAG = "BleKnock"
    private const val DEFAULT_TIMEOUT_MS = 12_000L

    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(
        context: Context,
        deviceAddress: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ByteArray? {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw IllegalStateException("BluetoothManager unavailable")
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth disabled")
        val device = adapter.getRemoteDevice(deviceAddress)

        val connected = CompletableDeferred<Boolean>()
        val servicesReady = CompletableDeferred<Boolean>()
        val readResult = CompletableDeferred<ByteArray?>()
        var gatt: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "$deviceAddress connected (status=$status)")
                        if (!connected.isCompleted) connected.complete(true)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "$deviceAddress disconnected (status=$status)")
                        if (!connected.isCompleted) connected.complete(false)
                        if (!readResult.isCompleted) readResult.complete(null)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Log.d(TAG, "services discovered: status=$status count=${g.services.size}")
                servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                // Pre-API-33 callback. Newer Android versions deliver the value
                // via the four-arg override below.
                if (!readResult.isCompleted) {
                    val bytes = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                    Log.d(TAG, "read (legacy): status=$status, len=${bytes?.size}")
                    readResult.complete(bytes)
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (!readResult.isCompleted) {
                    val bytes = if (status == BluetoothGatt.GATT_SUCCESS) value else null
                    Log.d(TAG, "read (API33+): status=$status, len=${bytes?.size}")
                    readResult.complete(bytes)
                }
            }
        }

        return try {
            withTimeout(timeoutMs) {
                gatt = device.connectGatt(context, /* autoConnect = */ false, callback)
                    ?: throw IllegalStateException("connectGatt returned null")

                val isConnected = connected.await()
                if (!isConnected) return@withTimeout null

                if (!gatt!!.discoverServices()) {
                    Log.w(TAG, "discoverServices returned false")
                    return@withTimeout null
                }
                val servicesOk = servicesReady.await()
                if (!servicesOk) return@withTimeout null

                val service = gatt!!.getService(serviceUuid)
                if (service == null) {
                    Log.w(TAG, "service $serviceUuid not found")
                    return@withTimeout null
                }
                val char = service.getCharacteristic(characteristicUuid)
                if (char == null) {
                    Log.w(TAG, "characteristic $characteristicUuid not found")
                    return@withTimeout null
                }
                if (!gatt!!.readCharacteristic(char)) {
                    Log.w(TAG, "readCharacteristic returned false")
                    return@withTimeout null
                }
                readResult.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "knock $deviceAddress timed out after ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            Log.w(TAG, "knock $deviceAddress failed: ${e.message}")
            null
        } finally {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
        }
    }
}
