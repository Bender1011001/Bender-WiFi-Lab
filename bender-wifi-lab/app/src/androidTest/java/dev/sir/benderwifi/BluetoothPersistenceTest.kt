package dev.sir.benderwifi

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.bluetooth.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BluetoothPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun observation(address: String = "02:00:00:00:00:01", seen: Long = 1000) =
        BleObservation(address, "SYNTHETIC PRIVATE NAME", -41, seen, seen * 1_000_000, true, listOf("1800"), "deadbeef", "none")
    private fun pending(id: String, address: String? = null) = BleSessionRecord(id, if (address == null) "scan" else "inspect", "running", 1000, null, address, "Unspecified", "", "{}")
    private fun completed(record: BleSessionRecord, observations: List<BleObservation>) = record.copy(status = "completed", endedAt = 1100,
        resultJson = JSONObject().put("observations", JSONArray(observations.map { it.json() })).toString())

    @Test fun scanInventoryAndNotesSurviveReopenWithoutNameMerging() = runBlocking {
        val name = "ble-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, BluetoothDatabase::class.java, name).build()
        try {
            var store = BluetoothStore(db)
            val observations = listOf(observation(), observation("02:00:00:00:00:02"))
            val record = pending("test-scan")
            store.begin(record); store.finish(completed(record, observations), observations)
            store.saveNote(observations[0].address, "SYNTHETIC model and firmware notes")
            db.close()
            db = Room.databaseBuilder(context, BluetoothDatabase::class.java, name).build(); store = BluetoothStore(db)
            assertEquals(2, store.devices.first().size)
            assertEquals(1, store.sessions.first().size)
            assertEquals(1, store.encounters(observations[0].address).first().size)
            assertEquals(1, store.encounters(observations[1].address).first().size)
            assertEquals("SYNTHETIC model and firmware notes", store.note(observations[0].address).first()!!.text)
            assertEquals("deadbeef", JSONObject(store.devices.first().first().advertisementJson).getString("advertisement_hex"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun processDeathReconciliationIsIdempotentAndPreservesTerminalRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        try {
            val store = BluetoothStore(db)
            val done = pending("done")
            store.begin(done); store.finish(completed(done, emptyList()), emptyList())
            store.begin(pending("unfinished", observation().address))
            assertEquals(1, store.reconcile()); assertEquals(0, store.reconcile())
            assertEquals("completed", db.dao().session("done")!!.status)
            assertEquals("interrupted", db.dao().session("unfinished")!!.status)
            assertTrue(db.dao().session("unfinished")!!.detail.contains("not restarted"))
        } finally { db.close() }
    }

    @Test fun olderSightingsAndDuplicateCompletionCannotRewriteSavedWork() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        try {
            val store = BluetoothStore(db)
            val first = pending("new"); val observed = observation(seen = 3000)
            store.begin(first); store.finish(completed(first, listOf(observed)), listOf(observed))
            val old = pending("old"); val previous = observation(seen = 1000)
            store.begin(old); store.finish(completed(old, listOf(previous)), listOf(previous))
            assertEquals(3000L, store.devices.first().single().lastSeen)
            assertEquals(1000L, store.devices.first().single().firstSeen)
            assertEquals(listOf(3000L, 1000L), store.encounters(observed.address).first().map { it.observedAt })
            assertTrue(runCatching { store.finish(completed(first, emptyList()), emptyList()) }.isFailure)
            assertEquals(2, store.sessions.first().size)
        } finally { db.close() }
    }

    @Test fun metadataExportOmitsNamesAddressesRawDataNotesAndUnknownFields() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        try {
            val store = BluetoothStore(db); val record = pending("export-test"); val observed = observation()
            store.begin(record)
            val result = JSONObject(completed(record, listOf(observed)).resultJson)
            result.put("password", "SYNTHETIC UNKNOWN SECRET")
            store.finish(record.copy(status = "completed", resultJson = result.toString(), detail = "PRIVATE ERROR DETAIL"), listOf(observed))
            store.saveNote(observed.address, "PRIVATE NOTE")
            val exported = store.exportMetadata()
            listOf(observed.address, observed.name!!, "deadbeef", "SYNTHETIC UNKNOWN SECRET", "PRIVATE ERROR DETAIL", "PRIVATE NOTE").forEach { assertFalse(it, exported.contains(it)) }
            assertEquals("device-1", JSONObject(exported).getJSONArray("sessions").getJSONObject(0).getJSONArray("observations").getJSONObject(0).getString("device"))
        } finally { db.close() }
    }

    @Test fun inspectionHistoryAndExportRetainEvidenceLimitsAndAddressJoins() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        try {
            val store = BluetoothStore(db); val address = observation().address
            val scan = pending("scan"); store.begin(scan); store.finish(completed(scan, listOf(observation())), listOf(observation()))
            val record = pending("inspect", address)
            val snapshot = GattSnapshot(address, "none", "bonded", emptyList())
            store.begin(record); store.finish(record.copy(status = "completed", resultJson = JSONObject().put("snapshot", snapshot.json()).toString()), emptyList())
            assertEquals(1, store.inspections(address).first().size)
            assertEquals(0, store.inspections("02:00:00:00:00:02").first().size)
            val rows = JSONObject(store.exportMetadata()).getJSONArray("sessions")
            val inspection = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("operation") == "inspect" }
            assertEquals("device-1", inspection.getString("device"))
            assertEquals("untested", inspection.getJSONObject("snapshot").getString("authorization"))
            assertFalse(inspection.getJSONObject("snapshot").has("address"))
        } finally { db.close() }
    }

    @Test fun exportBudgetIsExplicitAndDoesNotPretendOmittedWorkWasIncluded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BluetoothDatabase::class.java).build()
        try {
            val store = BluetoothStore(db)
            repeat(20) { index ->
                val record = pending("budget-$index")
                store.begin(record); store.finish(completed(record, listOf(observation())), listOf(observation()))
            }
            val text = store.exportMetadata(1024)
            val result = JSONObject(text)
            assertTrue(text.toByteArray(Charsets.UTF_8).size <= 1024)
            assertEquals(20, result.getInt("available_operations"))
            assertTrue(result.getBoolean("limit_reached"))
            assertEquals(result.getJSONArray("sessions").length(), result.getInt("included_operations"))
            assertTrue(result.getInt("included_operations") < 20)
        } finally { db.close() }
    }
}
