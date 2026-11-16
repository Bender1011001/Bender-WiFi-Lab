package dev.sir.benderwifi.bluetooth

import org.junit.Assert.*
import org.junit.Test

class BluetoothEvidenceTest {
    private fun observation(address: String = "02:00:00:00:00:01", timestamp: Long = 10, name: String? = "Same name") =
        BleObservation(address, name, -50, 1000, timestamp, true, listOf("1800"), "0102", "none")
    private fun snapshot(properties: Int = 2, bond: String = "none") = GattSnapshot("02:00:00:00:00:01", bond, bond,
        listOf(GattServiceInfo("00001800-0000-1000-8000-00805f9b34fb", 1, 0,
            listOf(GattCharacteristicInfo("00002a00-0000-1000-8000-00805f9b34fb", 2, properties, listOf("2901"))))))

    @Test fun rotatingAddressesAndMatchingNamesAreNotMerged() {
        val accumulator = BleAccumulator()
        accumulator.add(observation())
        accumulator.add(observation("02:00:00:00:00:02"))
        assertEquals(2, accumulator.snapshot().size)
    }
    @Test fun outOfOrderCallbacksCannotReplaceNewerEvidence() {
        val accumulator = BleAccumulator()
        accumulator.add(observation(timestamp = 20, name = "new"))
        assertFalse(accumulator.add(observation(timestamp = 10, name = "old")))
        assertEquals("new", accumulator.snapshot().single().name)
    }
    @Test fun capacityIsBoundedButExistingAddressesCanRefresh() {
        val accumulator = BleAccumulator(1)
        accumulator.add(observation())
        assertFalse(accumulator.add(observation("02:00:00:00:00:02")))
        assertTrue(accumulator.add(observation(timestamp = 30)))
        assertEquals(1, accumulator.dropped)
        assertEquals(30L, accumulator.close().single().elapsedNanos)
        assertFalse(accumulator.add(observation(timestamp = 40)))
        assertEquals(30L, accumulator.snapshot().single().elapsedNanos)
    }
    @Test fun namesAreDisplaySafeAndPayloadsAreBounded() {
        val accumulator = BleAccumulator()
        accumulator.add(observation(name = "bulb\n\u202e" + "x".repeat(300)).copy(advertisementHex = "ff".repeat(4000)))
        val value = accumulator.snapshot().single()
        assertEquals(120, value.name!!.length)
        assertFalse(value.name!!.contains('\n'))
        assertFalse(value.name!!.contains('\u202e'))
        assertEquals(3300, value.advertisementHex.length)
    }
    @Test fun comparisonDetectsPropertiesWithoutInventingAuthorization() {
        val diff = BluetoothEvidence.compare(snapshot(), snapshot(10, "bonded"))
        assertTrue(diff.any { it.startsWith("Metadata changed:") })
        assertTrue(diff.any { it.contains("none → bonded") })
        val json = snapshot(10, "bonded").json()
        assertEquals("untested", json.getString("authorization"))
        assertEquals("not_observed", json.getString("link_encryption"))
    }
    @Test fun snapshotRoundTripAndPrivateProjection() {
        val expected = snapshot()
        assertEquals(expected, BluetoothEvidence.snapshot(expected.json()))
        assertFalse(expected.json(false).has("address"))
        assertFalse(observation().json(false).has("advertisement_hex"))
        assertFalse(observation().json(false).has("advertised_name"))
    }
    @Test fun cannotCompareDifferentObservedAddresses() {
        assertTrue(runCatching { BluetoothEvidence.compare(snapshot(), snapshot().copy(address = "02:00:00:00:00:02")) }.isFailure)
    }
    @Test fun duplicateServiceInstancesRemainDistinct() {
        val first = snapshot()
        val next = first.copy(services = first.services + first.services.single().copy(instance = 3))
        assertEquals(2, BluetoothEvidence.compare(first, next).count { it.startsWith("Added") })
    }
    @Test fun serviceOrderingIsNotAChange() {
        val first = snapshot().let { it.copy(services = it.services + it.services.single().copy(instance = 3)) }
        assertEquals(listOf("No service metadata or Android bond-state differences observed"), BluetoothEvidence.compare(first, first.copy(services = first.services.reversed())))
    }
    @Test fun rejectsBadAddressesAndFormatsUnsignedBytes() {
        assertTrue(runCatching { BluetoothEvidence.address("not an address") }.isFailure)
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothEvidence.address("aa:bb:cc:dd:ee:ff"))
        assertEquals("00ff80", BluetoothEvidence.hex(byteArrayOf(0, -1, -128)))
    }
    @Test fun permissionsMatchAndroidVersionWithoutRoot() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"), BluetoothPermissions.required(30).toList())
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), BluetoothPermissions.required(35).toList())
    }
}
