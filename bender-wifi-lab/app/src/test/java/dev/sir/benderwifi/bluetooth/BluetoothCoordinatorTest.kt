package dev.sir.benderwifi.bluetooth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class BluetoothCoordinatorTest {
    private class Store : BluetoothPersistence {
        var begins = 0
        var finished: BleSessionRecord? = null
        var observations = emptyList<BleObservation>()
        var beginFailure = false
        var finishFailure = false
        var recoverCount = 0
        override suspend fun reconcile() = recoverCount
        override suspend fun begin(record: BleSessionRecord) { if (beginFailure) error("storage full"); begins++ }
        override suspend fun finish(record: BleSessionRecord, observations: List<BleObservation>) {
            if (finishFailure) error("storage full")
            finished = record; this.observations = observations
        }
    }
    private class Lease : BluetoothLease {
        var owner: String? = null
        var blocked: String? = null
        var released = 0
        override fun acquire(id: String): String? {
            if (blocked != null) return blocked
            check(owner == null); owner = id; return null
        }
        override fun release(id: String) { check(owner == id); owner = null; released++ }
    }
    private class Transport : BleTransport {
        var scans = 0; var inspections = 0; var closes = 0
        var callback: ((BleObservation) -> Unit)? = null
        var scanFailure = false
        var waitInspection = false
        override suspend fun scan(onObservation: (BleObservation) -> Unit) {
            scans++; callback = onObservation
            try { if (scanFailure) error("scan code 2"); awaitCancellation() } finally { closes++ }
        }
        override suspend fun inspect(address: String): GattSnapshot {
            inspections++
            try {
                if (waitInspection) awaitCancellation()
                return GattSnapshot(address, "none", "none", emptyList())
            } finally { closes++ }
        }
    }
    private val observation = BleObservation("02:00:00:00:00:01", "SYNTHETIC", -40, 100, 100, true, emptyList(), "", "none")
    private suspend fun idle(controller: BluetoothCoordinator) = withTimeout(2000) { controller.state.first { !it.busy } }

    @Test fun cancellationClosesTransportBeforeLeaseReleaseAndSavesPartialEvidence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = Transport(); val store = Store(); val lease = Lease()
        try {
            val controller = BluetoothCoordinator(transport, store, lease, scope)
            controller.scan(); transport.callback!!(observation)
            controller.cancel(); idle(controller)
            assertEquals(1, transport.closes); assertNull(lease.owner)
            assertEquals("cancelled", store.finished!!.status)
            assertEquals(1, store.observations.size)
            transport.callback!!(observation.copy(address = "02:00:00:00:00:02"))
            assertEquals(1, controller.state.value.observations.size)
        } finally { scope.cancel() }
    }
    @Test fun repeatedStartCannotLaunchASecondOperation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport(); val controller = BluetoothCoordinator(transport, Store(), Lease(), scope)
            controller.scan(); controller.scan(); controller.inspect(observation.address, "Unspecified")
            assertEquals(1, transport.scans); assertEquals(0, transport.inspections)
            controller.cancel(); idle(controller)
        } finally { scope.cancel() }
    }
    @Test fun failedLeaseDoesNotCreateEvidenceOrTouchTransport() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport(); val store = Store(); val lease = Lease().apply { blocked = "Wi-Fi owns the radio" }
            val controller = BluetoothCoordinator(transport, store, lease, scope)
            controller.scan()
            assertEquals(0, store.begins); assertEquals(0, transport.scans)
            assertEquals("Wi-Fi owns the radio", controller.state.value.phase)
        } finally { scope.cancel() }
    }
    @Test fun persistenceFailureBeforeStartCannotReachTheRadio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport(); val store = Store().apply { beginFailure = true }; val lease = Lease()
            val controller = BluetoothCoordinator(transport, store, lease, scope)
            controller.scan(); idle(controller)
            assertEquals(0, transport.scans); assertNull(lease.owner)
            assertTrue(controller.state.value.phase.contains("storage full"))
        } finally { scope.cancel() }
    }
    @Test fun failureDuringFinalSaveStillReleasesTheLease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport(); val store = Store().apply { finishFailure = true }; val lease = Lease()
            val controller = BluetoothCoordinator(transport, store, lease, scope)
            controller.inspect(observation.address, "After pairing"); idle(controller)
            assertEquals(1, transport.closes); assertNull(lease.owner)
            assertTrue(controller.state.value.phase.contains("saving evidence failed"))
        } finally { scope.cancel() }
    }
    @Test fun scanFailureIsSavedAndNeverRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport().apply { scanFailure = true }; val store = Store()
            val controller = BluetoothCoordinator(transport, store, Lease(), scope)
            controller.scan(); idle(controller)
            assertEquals("failed", store.finished!!.status); assertEquals(1, transport.scans)
        } finally { scope.cancel() }
    }
    @Test fun inspectionTimeoutClosesAndRecordsAnIncompleteOutcome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport().apply { waitInspection = true }; val store = Store()
            val controller = BluetoothCoordinator(transport, store, Lease(), scope, inspectionMillis = 10)
            controller.inspect(observation.address, "Unspecified"); idle(controller)
            assertEquals("timed_out", store.finished!!.status); assertEquals(1, transport.closes)
        } finally { scope.cancel() }
    }
    @Test fun boundedScanTimeoutIsNormalCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport(); val store = Store()
            val controller = BluetoothCoordinator(transport, store, Lease(), scope, scanMillis = 10)
            controller.scan(); idle(controller)
            assertEquals("completed", store.finished!!.status); assertEquals(1, transport.closes)
        } finally { scope.cancel() }
    }
    @Test fun startupRecordsInterruptedWorkWithoutReplayingIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = Transport(); val store = Store().apply { recoverCount = 2 }
            val controller = BluetoothCoordinator(transport, store, Lease(), scope)
            assertTrue(controller.state.value.phase.startsWith("2 interrupted"))
            assertEquals(0, transport.scans); assertEquals(0, transport.inspections)
        } finally { scope.cancel() }
    }
    @Test fun cancelBeforeDispatcherRunsCannotLeakReservation() {
        val queue = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) } }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        try {
            val transport = Transport(); val lease = Lease(); val store = Store()
            val controller = BluetoothCoordinator(transport, store, lease, scope)
            drain(); controller.scan(); controller.cancel(); drain()
            assertNull(lease.owner); assertEquals(0, transport.scans)
            assertEquals("cancelled", store.finished!!.status)
        } finally { scope.cancel(); drain() }
    }
}
