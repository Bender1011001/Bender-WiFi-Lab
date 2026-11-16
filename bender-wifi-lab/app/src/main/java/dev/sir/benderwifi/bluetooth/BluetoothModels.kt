package dev.sir.benderwifi.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** An address observed through Android, never a claim of persistent device identity. */
data class BleObservation(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val observedAt: Long,
    val elapsedNanos: Long,
    val connectable: Boolean?,
    val serviceUuids: List<String>,
    val advertisementHex: String,
    val bondState: String
) {
    fun json(includePrivate: Boolean = true): JSONObject = JSONObject()
        .put("observed_ms", observedAt).put("elapsed_nanos", elapsedNanos)
        .put("rssi_dbm", rssi ?: JSONObject.NULL).put("connectable", connectable ?: JSONObject.NULL)
        .put("service_uuids", JSONArray(serviceUuids)).put("android_bond_state", bondState)
        .also { if (includePrivate) it.put("address", address).put("advertised_name", name ?: JSONObject.NULL)
            .put("advertisement_hex", advertisementHex) }
}

data class GattCharacteristicInfo(val uuid: String, val instance: Int, val properties: Int, val descriptors: List<String>) {
    fun json() = JSONObject().put("uuid", uuid).put("instance", instance).put("properties", properties)
        .put("descriptors", JSONArray(descriptors))
    fun propertyLabels(): String = listOf(1 to "broadcast", 2 to "read", 4 to "write without response",
        8 to "write", 16 to "notify", 32 to "indicate", 64 to "signed write", 128 to "extended")
        .filter { properties and it.first != 0 }.joinToString { it.second }.ifEmpty { "none advertised" }
}

data class GattServiceInfo(val uuid: String, val instance: Int, val type: Int, val characteristics: List<GattCharacteristicInfo>) {
    fun json() = JSONObject().put("uuid", uuid).put("instance", instance).put("type", type)
        .put("characteristics", JSONArray(characteristics.map { it.json() }))
}

data class GattSnapshot(val address: String, val bondAtStart: String, val bondAtEnd: String, val services: List<GattServiceInfo>) {
    fun json(includePrivate: Boolean = true) = JSONObject().put("services", JSONArray(services.map { it.json() }))
        .put("bond_at_start", bondAtStart).put("bond_at_end", bondAtEnd)
        .put("authorization", "untested").put("link_encryption", "not_observed")
        .put("source", "android_gatt_discovery_cache_possible")
        .also { if (includePrivate) it.put("address", address) }
}

object BluetoothEvidence {
    val addressPattern = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    val stages = listOf("Unspecified", "Before pairing", "After pairing", "After restart", "After ownership change")
    fun address(value: String): String = value.uppercase(Locale.ROOT).also {
        require(addressPattern.matches(it)) { "Invalid Bluetooth address" }
    }
    fun displayName(value: String?): String? = value?.filterNot {
        it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
    }?.take(120)?.takeIf { it.isNotBlank() }
    fun bond(value: Int): String = when (value) { 10 -> "none"; 11 -> "bonding"; 12 -> "bonded"; else -> "unknown" }
    fun hex(bytes: ByteArray): String = bytes.take(1650).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun snapshot(value: JSONObject): GattSnapshot {
        val services = value.optJSONArray("services") ?: JSONArray()
        require(services.length() <= 128) { "Too many services" }
        var count = 0
        return GattSnapshot(address(value.getString("address")), value.getString("bond_at_start"), value.getString("bond_at_end"),
            (0 until services.length()).map { i ->
                val service = services.getJSONObject(i)
                val chars = service.getJSONArray("characteristics")
                count += chars.length(); require(count <= 512) { "Too many characteristics" }
                GattServiceInfo(service.getString("uuid"), service.getInt("instance"), service.getInt("type"),
                    (0 until chars.length()).map { j ->
                        val char = chars.getJSONObject(j); val desc = char.getJSONArray("descriptors")
                        require(desc.length() <= 64) { "Too many descriptors" }
                        GattCharacteristicInfo(char.getString("uuid"), char.getInt("instance"), char.getInt("properties"),
                            (0 until desc.length()).map { desc.getString(it) })
                    })
            })
    }

    /** Metadata differences are observations, not authorization failures. */
    fun compare(before: GattSnapshot, after: GattSnapshot): List<String> {
        require(before.address == after.address) { "Compare the same observed address; rotating addresses are not merged" }
        fun flatten(snapshot: GattSnapshot): Map<String, String> = buildMap {
            snapshot.services.forEach { service ->
                val key = "${service.uuid}#${service.instance}"
                put("Service $key", service.type.toString())
                service.characteristics.forEach { char ->
                    put("Characteristic $key/${char.uuid}#${char.instance}",
                        "properties ${char.properties} (${char.propertyLabels()}); descriptors ${char.descriptors.sorted().joinToString().ifEmpty { "none" }}")
                }
            }
        }
        val old = flatten(before); val new = flatten(after)
        return buildList {
            if (before.bondAtEnd != after.bondAtEnd) add("Android bond state: ${before.bondAtEnd} → ${after.bondAtEnd}")
            (new.keys - old.keys).sorted().forEach { add("Added $it") }
            (old.keys - new.keys).sorted().forEach { add("Removed $it") }
            (old.keys intersect new.keys).filter { old[it] != new[it] }.sorted().forEach { add("Metadata changed: $it\n${old[it]} → ${new[it]}") }
            if (isEmpty()) add("No service metadata or Android bond-state differences observed")
        }
    }
}

/** Bounded, thread-safe accumulation; late callbacks cannot mutate a finished run. */
class BleAccumulator(private val limit: Int = 256) {
    private val values = linkedMapOf<String, BleObservation>()
    private var closed = false
    var dropped: Int = 0
        private set
    @Synchronized fun add(value: BleObservation): Boolean {
        if (closed) return false
        val key = BluetoothEvidence.address(value.address)
        if (key !in values && values.size >= limit) { dropped++; return false }
        if ((values[key]?.elapsedNanos ?: -1) > value.elapsedNanos) return false
        values[key] = value.copy(address = key, name = BluetoothEvidence.displayName(value.name),
            serviceUuids = value.serviceUuids.take(128), advertisementHex = value.advertisementHex.take(3300))
        return true
    }
    @Synchronized fun snapshot(): List<BleObservation> = values.values.toList()
    @Synchronized fun close(): List<BleObservation> { closed = true; return snapshot() }
}
