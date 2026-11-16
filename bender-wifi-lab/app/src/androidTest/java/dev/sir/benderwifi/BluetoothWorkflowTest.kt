package dev.sir.benderwifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.bluetooth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production UI with isolated Room and a synthetic transport; no radio APIs. */
class BluetoothWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val address = "02:00:00:00:00:41"
    private val ready = BluetoothAccess(true, true, true, true, "SYNTHETIC access ready")
    private class Lease : BluetoothLease {
        override fun acquire(id: String): String? = null
        override fun release(id: String) {}
    }
    private class Transport : BleTransport {
        var scans = 0; var inspections = 0
        override suspend fun scan(onObservation: (BleObservation) -> Unit) { scans++; awaitCancellation() }
        override suspend fun inspect(address: String): GattSnapshot {
            inspections++
            return GattSnapshot(address, "none", "none", listOf(GattServiceInfo("00001800-0000-1000-8000-00805f9b34fb", 1, 0, emptyList())))
        }
    }

    @Test fun permissionStateDoesNotStartTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var requested = false
        val transport = Transport()
        try {
            val store = BluetoothStore(db); val controller = BluetoothCoordinator(transport, store, Lease(), scope)
            compose.setContent { BluetoothScreen(store, controller, ready.copy(permissions = false, message = "Nearby devices permission is needed"), onPermissions = { requested = true }, onSettings = {}, onBack = {}, onExport = {}) }
            compose.waitUntil(10_000) { !controller.state.value.busy }
            compose.onNodeWithTag("ble-device-list").performScrollToNode(hasText("Grant Bluetooth permission"))
            compose.onNodeWithText("Grant Bluetooth permission").performClick()
            compose.runOnIdle { assertTrue(requested); assertEquals(0, transport.scans); assertEquals(0, transport.inspections) }
            compose.onNodeWithText("Research").performClick()
            compose.onNodeWithText("Research catalog").assertIsDisplayed()
        } finally { scope.cancel(); db.close() }
    }

    @Test fun unavailableHardwareKeepsHistoryAndResearchUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val transport = Transport()
        try {
            val store = BluetoothStore(db); val controller = BluetoothCoordinator(transport, store, Lease(), scope)
            compose.setContent { BluetoothScreen(store, controller, BluetoothAccess(false, false, false, false, "BLE unavailable"), onPermissions = {}, onSettings = {}, onBack = {}, onExport = {}) }
            compose.waitUntil(10_000) { !controller.state.value.busy }
            compose.onNodeWithText("BLE hardware is unavailable. Saved work and research remain accessible.").assertExists()
            compose.onNodeWithText("Discover BLE · 15 seconds").assertDoesNotExist()
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("No saved Bluetooth operations").assertExists()
            compose.onNodeWithText("Research").performClick()
            compose.onNodeWithText("Research catalog").assertIsDisplayed()
            assertEquals(0, transport.scans); assertEquals(0, transport.inspections)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun dossierSavesNotesInspectsAndComparesWithoutInventedSecurityClaims() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val transport = Transport()
        try {
            val store = BluetoothStore(db)
            runBlocking {
                val value = BleObservation(address, "SYNTHETIC BULB", -45, 1000, 1000, true, emptyList(), "0201", "none")
                val session = BleSessionRecord("synthetic-scan", "scan", "running", 1000, null, null, "Unspecified", "", "{}")
                store.begin(session); store.finish(session.copy(status = "completed", resultJson = JSONObject().put("observations", JSONArray(listOf(value.json()))).toString()), listOf(value))
            }
            val controller = BluetoothCoordinator(transport, store, Lease(), scope)
            compose.setContent { BluetoothScreen(store, controller, ready, onPermissions = {}, onSettings = {}, onBack = {}, onExport = {}) }
            compose.waitUntil(10_000) { !controller.state.value.busy }
            compose.onNodeWithTag("ble-device-list").performScrollToNode(hasText("SYNTHETIC BULB"))
            compose.onNodeWithText("SYNTHETIC BULB").performClick()
            compose.onNodeWithTag("ble-dossier-list").performScrollToNode(hasText("Connect & inspect"))
            compose.onNodeWithText("Connect & inspect").performClick()
            compose.waitUntil(10_000) { transport.inspections == 1 && !controller.state.value.busy }
            compose.onNodeWithTag("ble-dossier-list").performScrollToNode(hasText("Model, firmware and observations"))
            compose.onNodeWithText("Model, firmware and observations").performTextInput("Synthetic model v1")
            compose.onNodeWithText("Save device notes").performScrollTo().performClick()
            compose.onNodeWithText("Device notes saved").assertIsDisplayed()
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithTag("ble-dossier-list").performScrollToNode(hasText("Use as baseline"))
            compose.onNodeWithText("Use as baseline").performClick()
            compose.onNodeWithText("Selected baseline").assertExists()
            compose.onNodeWithTag("ble-dossier-list").performScrollToNode(hasText("Authorization untested · link encryption not observed · cache possible"))
            compose.onNodeWithText("Authorization untested · link encryption not observed · cache possible").assertIsDisplayed()
            assertEquals("Synthetic model v1", runBlocking { store.note(address).first()!!.text })
            compose.onNodeWithTag("ble-dossier-list").performScrollToNode(hasText("Stage: Unspecified"))
            compose.onNodeWithText("Stage: Unspecified").performClick()
            compose.onNodeWithText("After pairing").performClick()
            compose.onNodeWithText("Connect & inspect").performScrollTo().performClick()
            compose.waitUntil(10_000) { transport.inspections == 2 && !controller.state.value.busy }
            compose.onNodeWithTag("ble-dossier-list").performScrollToNode(hasText("Compare with baseline"))
            saveBluetoothScreenshot("bluetooth-dossier-synthetic")
            compose.onNodeWithText("Compare with baseline").performClick()
            compose.onNodeWithText("No service metadata or Android bond-state differences observed", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Metadata differences alone are not an authorization finding.", substring = true).assertIsDisplayed()
            saveBluetoothScreenshot("bluetooth-comparison-synthetic")
            compose.onNodeWithText("OK").performClick()
            assertEquals(0, transport.scans)
        } finally { scope.cancel(); db.close() }
    }
}
