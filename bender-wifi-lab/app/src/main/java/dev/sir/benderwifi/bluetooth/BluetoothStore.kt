package dev.sir.benderwifi.bluetooth

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "ble_devices")
data class BleDeviceRecord(@PrimaryKey val address: String, val name: String?, val firstSeen: Long,
    val lastSeen: Long, val rssi: Int?, val advertisementJson: String)

@Entity(tableName = "ble_sessions", indices = [Index("address")])
data class BleSessionRecord(@PrimaryKey val id: String, val operation: String, val status: String,
    val startedAt: Long, val endedAt: Long?, val address: String?, val stage: String,
    val detail: String, val resultJson: String)

@Entity(tableName = "ble_notes")
data class BleNoteRecord(@PrimaryKey val address: String, val text: String)

@Entity(tableName = "ble_encounters", primaryKeys = ["sessionId", "address"], indices = [Index("address")])
data class BleEncounterRecord(val sessionId: String, val address: String, val observedAt: Long, val observationJson: String)

@Dao
interface BluetoothDao {
    @Query("SELECT * FROM ble_devices ORDER BY lastSeen DESC") fun devices(): Flow<List<BleDeviceRecord>>
    @Query("SELECT * FROM ble_sessions ORDER BY startedAt DESC, id DESC LIMIT 200") fun sessions(): Flow<List<BleSessionRecord>>
    @Query("SELECT * FROM ble_sessions WHERE address=:address ORDER BY startedAt DESC, id DESC LIMIT 100") fun inspections(address: String): Flow<List<BleSessionRecord>>
    @Query("SELECT * FROM ble_notes WHERE address=:address") fun note(address: String): Flow<BleNoteRecord?>
    @Query("SELECT * FROM ble_encounters WHERE address=:address ORDER BY observedAt DESC, sessionId DESC LIMIT 100") fun encounters(address: String): Flow<List<BleEncounterRecord>>
    @Query("SELECT * FROM ble_devices WHERE address=:address") suspend fun device(address: String): BleDeviceRecord?
    @Query("SELECT * FROM ble_sessions WHERE id=:id") suspend fun session(id: String): BleSessionRecord?
    @Query("SELECT id FROM ble_sessions ORDER BY startedAt DESC, id DESC LIMIT 1000") suspend fun exportSessionIds(): List<String>
    @Query("SELECT COUNT(*) FROM ble_sessions") suspend fun sessionCount(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun begin(record: BleSessionRecord)
    @Update suspend fun update(record: BleSessionRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDevice(record: BleDeviceRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putNote(record: BleNoteRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putEncounter(record: BleEncounterRecord)
    @Query("UPDATE ble_sessions SET status='interrupted', endedAt=:now, detail='App process ended; operation was not restarted' WHERE status='running'")
    suspend fun interruptRunning(now: Long): Int
}

@Database(entities = [BleDeviceRecord::class, BleSessionRecord::class, BleNoteRecord::class, BleEncounterRecord::class], version = 1, exportSchema = false)
abstract class BluetoothDatabase : RoomDatabase() { abstract fun dao(): BluetoothDao }

interface BluetoothPersistence {
    suspend fun reconcile(): Int
    suspend fun begin(record: BleSessionRecord)
    suspend fun finish(record: BleSessionRecord, observations: List<BleObservation>)
}

class BluetoothStore(val db: BluetoothDatabase) : BluetoothPersistence {
    val devices = db.dao().devices()
    val sessions = db.dao().sessions()
    fun inspections(address: String) = db.dao().inspections(address)
    fun note(address: String) = db.dao().note(address)
    fun encounters(address: String) = db.dao().encounters(address)
    override suspend fun reconcile() = db.dao().interruptRunning(System.currentTimeMillis())
    override suspend fun begin(record: BleSessionRecord) = db.dao().begin(record)
    override suspend fun finish(record: BleSessionRecord, observations: List<BleObservation>) = db.withTransaction {
        // A completed operation is immutable; callbacks cannot finalize it twice.
        check(db.dao().session(record.id)?.status == "running") { "Bluetooth session is not running" }
        for (observation in observations) {
            val address = BluetoothEvidence.address(observation.address)
            db.dao().putEncounter(BleEncounterRecord(record.id, address, observation.observedAt, observation.json().toString()))
            val previous = db.dao().device(address)
            if (previous == null || observation.observedAt >= previous.lastSeen) {
                db.dao().putDevice(BleDeviceRecord(address, observation.name ?: previous?.name,
                    minOf(previous?.firstSeen ?: observation.observedAt, observation.observedAt), observation.observedAt, observation.rssi,
                    observation.json().toString()))
            } else if (observation.observedAt < previous.firstSeen) {
                db.dao().putDevice(previous.copy(firstSeen = observation.observedAt))
            }
        }
        db.dao().update(record)
    }
    suspend fun saveNote(address: String, text: String) {
        require(text.length <= 10000) { "Notes are limited to 10,000 characters" }
        db.dao().putNote(BleNoteRecord(BluetoothEvidence.address(address), text))
    }
    suspend fun exportMetadata(maximumBytes: Int = 8 * 1024 * 1024): String {
        require(maximumBytes >= 1024) { "Export budget is too small" }
        val ids = db.dao().exportSessionIds()
        val available = db.dao().sessionCount()
        var bytes = 768 // Envelope, counts and limit descriptions; final UTF-8 size is checked below.
        val rows = JSONArray()
        val aliases = linkedMapOf<String, String>()
        fun alias(address: String) = aliases.getOrPut(address) { "device-${aliases.size + 1}" }
        for (id in ids) {
            val record = db.dao().session(id) ?: continue
            val result = JSONObject(record.resultJson)
            val clean = JSONObject().put("operation", record.operation).put("status", record.status)
                .put("started_ms", record.startedAt).put("ended_ms", record.endedAt ?: JSONObject.NULL)
                .put("researcher_stage", record.stage)
            record.address?.let { clean.put("device", alias(it)) }
            if (record.operation == "inspect" && result.has("snapshot")) {
                clean.put("snapshot", BluetoothEvidence.snapshot(result.getJSONObject("snapshot")).json(false))
            }
            val observations = result.optJSONArray("observations") ?: JSONArray()
            clean.put("observations", JSONArray((0 until observations.length()).map { i ->
                val value = observations.getJSONObject(i)
                // Typed projection: no names, raw advertisements, notes or arbitrary error strings.
                JSONObject().put("device", alias(value.getString("address")))
                    .put("observed_ms", value.getLong("observed_ms"))
                    .put("rssi_dbm", value.opt("rssi_dbm") ?: JSONObject.NULL)
                    .put("connectable", value.opt("connectable") ?: JSONObject.NULL)
                    .put("android_bond_state", value.optString("android_bond_state", "unknown"))
                    .put("service_uuids", value.optJSONArray("service_uuids") ?: JSONArray())
            }))
            val rowBytes = clean.toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + rowBytes > maximumBytes) break
            bytes += rowBytes
            rows.put(clean)
        }
        val output = JSONObject().put("bluetooth_export_schema", 1)
            .put("available_operations", available).put("included_operations", rows.length())
            .put("limit_reached", rows.length() < available)
            .put("limits", JSONArray(listOf("Latest 1000 operations within 8 MiB; no names, addresses, raw advertisements or notes",
                "UUIDs, timestamps and metadata can still identify a device; this is not an anonymity guarantee",
                "Android GATT cache may affect discovery; properties do not prove authorization or encryption")))
            .put("sessions", rows).toString()
        check(output.toByteArray(Charsets.UTF_8).size <= maximumBytes) { "Export exceeds its byte budget" }
        return output
    }
    companion object {
        @Volatile private var instance: BluetoothStore? = null
        fun get(context: Context): BluetoothStore = instance ?: synchronized(this) {
            instance ?: BluetoothStore(Room.databaseBuilder(context.applicationContext,
                BluetoothDatabase::class.java, "bender-bluetooth.db").build()).also { instance = it }
        }
    }
}
