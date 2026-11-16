package dev.sir.benderwifi

import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.radio.RadioController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Emulator only: a reservation must prevent both Wi-Fi service launch and import. */
class BluetoothOwnershipTest {
    @Test fun reservationBlocksWifiUntilItsOwnerReleasesIt(): Unit = runBlocking {
        RadioController.init(InstrumentationRegistry.getInstrumentation().targetContext)
        withTimeout(10_000) { RadioController.state.first { !it.busy } }
        val owner = "synthetic-bluetooth-owner"
        assertNull(RadioController.reserveBluetooth(owner))
        try {
            RadioController.start("probe")
            assertFalse(RadioController.state.value.busy)
            assertTrue(RadioController.state.value.phase.contains("Stop the Bluetooth operation"))
            var opened = false
            RadioController.copyForAnalysis { opened = true; error("Must not open a document") }
            assertFalse(opened)
            RadioController.releaseBluetooth("foreign-owner")
            assertNotNull(RadioController.reserveBluetooth("second-owner"))
        } finally { RadioController.releaseBluetooth(owner) }
        assertNull(RadioController.reserveBluetooth("second-owner"))
        RadioController.releaseBluetooth("second-owner")
    }
}
