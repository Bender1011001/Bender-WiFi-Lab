package dev.sir.benderwifi.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BluetoothAccess(val supported: Boolean, val permissions: Boolean, val enabled: Boolean, val locationReady: Boolean, val message: String) {
    val ready get() = supported && permissions && enabled && locationReady
}

object BluetoothPermissions {
    @SuppressLint("InlinedApi") // Caller supplies Build.VERSION.SDK_INT; constants are safe on the tested branch.
    fun required(sdk: Int): Array<String> = if (sdk >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

interface BleTransport {
    /** Runs until cancelled or a scan failure occurs. The coordinator owns the duration. */
    suspend fun scan(onObservation: (BleObservation) -> Unit)
    suspend fun inspect(address: String): GattSnapshot
}

@SuppressLint("MissingPermission") // Checked immediately before each operation; revocation still fails explicitly.
class AndroidBleTransport(private val context: Context) : BleTransport {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun access(): BluetoothAccess {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null
        val permissions = BluetoothPermissions.required(Build.VERSION.SDK_INT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val enabled = permissions && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val location = Build.VERSION.SDK_INT >= 31 || context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        val message = when {
            !supported -> "Bluetooth LE is unavailable on this device"
            !permissions -> if (Build.VERSION.SDK_INT >= 31) "Nearby devices permission is needed" else "Location permission is needed for Android 11 BLE scanning"
            !enabled -> "Turn on Bluetooth in Android settings"
            !location -> "Android 11 requires Location to be enabled for BLE scanning"
            else -> "Ready · Android BLE · root not required"
        }
        return BluetoothAccess(supported, permissions, enabled, location, message)
    }

    override suspend fun scan(onObservation: (BleObservation) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val access = access()
        if (!access.ready) { continuation.resumeWithException(IllegalStateException(access.message)); return@suspendCancellableCoroutine }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { continuation.resumeWithException(IllegalStateException("Android BLE scanner is unavailable")); return@suspendCancellableCoroutine }
        val active = AtomicBoolean(true)
        val lock = Any()
        lateinit var callback: ScanCallback
        fun close(): Boolean = synchronized(lock) {
            if (!active.getAndSet(false)) false else { runCatching { scanner.stopScan(callback) }; true }
        }
        callback = object : ScanCallback() {
            private fun accept(result: ScanResult) {
                if (!active.get() || !continuation.isActive) return
                try {
                    val record = result.scanRecord
                    val ageMs = ((SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000).coerceAtLeast(0)
                    onObservation(BleObservation(BluetoothEvidence.address(result.device.address),
                        BluetoothEvidence.displayName(record?.deviceName), result.rssi.takeIf { it in -127..20 },
                        (System.currentTimeMillis() - ageMs).coerceAtLeast(0), result.timestampNanos,
                        result.isConnectable, record?.serviceUuids.orEmpty().map { it.toString() },
                        BluetoothEvidence.hex(record?.bytes ?: byteArrayOf()), BluetoothEvidence.bond(result.device.bondState)))
                } catch (error: Exception) {
                    if (close() && continuation.isActive) continuation.resumeWithException(error)
                }
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::accept) }
            override fun onScanFailed(errorCode: Int) {
                if (close() && continuation.isActive) continuation.resumeWithException(IllegalStateException("Android scan failed (code $errorCode); it was not retried"))
            }
        }
        continuation.invokeOnCancellation { close() }
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0)
                .setLegacy(adapter?.isLeExtendedAdvertisingSupported != true).build()
            synchronized(lock) { if (active.get() && continuation.isActive) scanner.startScan(null, settings, callback) }
        } catch (error: Exception) {
            if (close() && continuation.isActive) continuation.resumeWithException(error)
        }
    }

    override suspend fun inspect(address: String): GattSnapshot = suspendCancellableCoroutine { continuation ->
        val access = access()
        if (!access.ready) { continuation.resumeWithException(IllegalStateException(access.message)); return@suspendCancellableCoroutine }
        val device = adapter?.getRemoteDevice(BluetoothEvidence.address(address))
        if (device == null) { continuation.resumeWithException(IllegalStateException("Bluetooth device is unavailable")); return@suspendCancellableCoroutine }
        val startBond = BluetoothEvidence.bond(device.bondState)
        val lock = Any()
        var active = true
        var connection: BluetoothGatt? = null
        fun dispose(gatt: BluetoothGatt) { runCatching { gatt.disconnect() }; runCatching { gatt.close() } }
        fun finish(gatt: BluetoothGatt?, snapshot: GattSnapshot? = null, error: Exception? = null) {
            synchronized(lock) {
                if (!active) return
                active = false
                (gatt ?: connection)?.let(::dispose)
                connection = null
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWithException(error)
                    else if (snapshot != null) continuation.resume(snapshot)
                }
            }
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                synchronized(lock) {
                    if (!active) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(gatt, error = IllegalStateException("GATT connection failed (status $status); no retry")); return
                    }
                    try {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> if (!gatt.discoverServices()) finish(gatt, error = IllegalStateException("Service discovery was not accepted"))
                            BluetoothProfile.STATE_DISCONNECTED -> finish(gatt, error = IllegalStateException("Device disconnected before service discovery completed"))
                        }
                    } catch (error: Exception) { finish(gatt, error = error) }
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                synchronized(lock) {
                    if (!active) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(gatt, error = IllegalStateException("GATT discovery failed (status $status)")); return
                    }
                    try {
                        require(gatt.services.size <= 128) { "Service metadata exceeds the preview limit" }
                        var count = 0
                        val services = gatt.services.map { service ->
                            count += service.characteristics.size
                            require(count <= 512) { "Characteristic metadata exceeds the preview limit" }
                            GattServiceInfo(service.uuid.toString(), service.instanceId, service.type, service.characteristics.map { char ->
                                require(char.descriptors.size <= 64) { "Descriptor metadata exceeds the preview limit" }
                                GattCharacteristicInfo(char.uuid.toString(), char.instanceId, char.properties, char.descriptors.map { it.uuid.toString() })
                            })
                        }
                        finish(gatt, GattSnapshot(address, startBond, BluetoothEvidence.bond(device.bondState), services))
                    } catch (error: Exception) { finish(gatt, error = error) }
                }
            }
        }
        continuation.invokeOnCancellation { finish(null) }
        synchronized(lock) {
            if (active && continuation.isActive) {
                try {
                    val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    if (!active) gatt?.let(::dispose) else if (gatt == null) finish(null, error = IllegalStateException("GATT connection could not start"))
                    else connection = gatt
                } catch (error: Exception) { finish(null, error = error) }
            }
        }
    }
}
