package dev.sir.benderwifi.bluetooth

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import dev.sir.benderwifi.radio.RadioController
import kotlinx.coroutines.*

/** No Bluetooth operation outlives the visible activity or restarts automatically. */
class BluetoothActivity : ComponentActivity() {
    private lateinit var transport: AndroidBleTransport
    private lateinit var store: BluetoothStore
    private lateinit var coordinator: BluetoothCoordinator
    private val access = mutableStateOf(BluetoothAccess(false, false, false, false, "Checking Bluetooth"))
    private val message = mutableStateOf<String?>(null)
    private val adapterChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAccess()
            if (!access.value.enabled) coordinator.cancel("Bluetooth became unavailable")
        }
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAccess() }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) { store.exportMetadata() }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: error("The selected destination could not be opened")
                }
                org.json.JSONObject(json)
            }.onSuccess { report ->
                message.value = "Exported ${report.getInt("included_operations")} of ${report.getInt("available_operations")} saved operations." +
                    (if (report.getBoolean("limit_reached")) " The size/count limit was reached." else "") +
                    " Names, addresses, raw advertisements and notes are omitted."
            }
                .onFailure { message.value = "Export failed: ${it.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = AndroidBleTransport(applicationContext)
        store = BluetoothStore.get(this)
        coordinator = BluetoothRuntime.get(transport, store)
        refreshAccess()
        setContent {
            BluetoothScreen(store, coordinator, access.value, message.value,
                onPermissions = { permissionRequest.launch(BluetoothPermissions.required(Build.VERSION.SDK_INT)) },
                onSettings = {
                    val intent = when {
                        !access.value.permissions -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        !access.value.locationReady && access.value.enabled -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        else -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    }
                    runCatching { startActivity(intent) }.onFailure { message.value = "Android settings could not be opened" }
                },
                onBack = { finish() }, onExport = { export.launch("bender-bluetooth-metadata.json") },
                onDismissMessage = { message.value = null })
        }
    }
    override fun onResume() { super.onResume(); if (::transport.isInitialized) refreshAccess() }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, adapterChanges, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onStop() {
        unregisterReceiver(adapterChanges)
        if (::coordinator.isInitialized) coordinator.cancel()
        super.onStop()
    }
    private fun refreshAccess() { access.value = transport.access() }
}

private object BluetoothRuntime {
    private var coordinator: BluetoothCoordinator? = null
    @Synchronized fun get(transport: BleTransport, store: BluetoothPersistence): BluetoothCoordinator = coordinator
        ?: BluetoothCoordinator(transport, store, object : BluetoothLease {
            override fun acquire(id: String) = RadioController.reserveBluetooth(id)
            override fun release(id: String) = RadioController.releaseBluetooth(id)
        }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { coordinator = it }
}
