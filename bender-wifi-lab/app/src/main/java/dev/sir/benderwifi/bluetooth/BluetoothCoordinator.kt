package dev.sir.benderwifi.bluetooth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface BluetoothLease {
    /** null means reserved; a non-null string explains why acquisition failed. */
    fun acquire(id: String): String?
    fun release(id: String)
}

data class BluetoothUiState(val busy: Boolean = true, val phase: String = "Checking saved Bluetooth sessions",
    val observations: List<BleObservation> = emptyList(), val operation: String? = null, val storageReady: Boolean = false)

/** Foreground-only coordinator. The transport closes before persistence and lease release. */
class BluetoothCoordinator(
    private val transport: BleTransport,
    private val store: BluetoothPersistence,
    private val lease: BluetoothLease,
    private val scope: CoroutineScope,
    private val scanMillis: Long = 15_000,
    private val inspectionMillis: Long = 20_000
) {
    private val mutable = MutableStateFlow(BluetoothUiState())
    val state: StateFlow<BluetoothUiState> = mutable
    private var job: Job? = null
    private var activeId: String? = null

    init {
        scope.launch {
            try {
                val interrupted = store.reconcile()
                mutable.value = BluetoothUiState(false, if (interrupted > 0) "$interrupted interrupted operation(s) recorded; none restarted" else "Ready for an explicit Bluetooth operation", storageReady = true)
            } catch (error: Exception) {
                mutable.value = BluetoothUiState(false, "Cannot open Bluetooth history: ${error.message}")
            }
        }
    }

    fun scan() = start("scan", null, "Unspecified")
    fun inspect(address: String, stage: String) = start("inspect", BluetoothEvidence.address(address), stage)
    fun cancel(reason: String = "User stopped or left Bluetooth Lab") { job?.cancel(CancellationException(reason)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start(operation: String, address: String?, stage: String) {
        if (mutable.value.busy || !mutable.value.storageReady) return
        require(stage in BluetoothEvidence.stages) { "Unknown observation stage" }
        val id = "ble_${UUID.randomUUID()}"
        val blocked = lease.acquire(id)
        if (blocked != null) { mutable.value = mutable.value.copy(phase = blocked); return }
        activeId = id
        mutable.value = BluetoothUiState(true, if (operation == "scan") "Scanning for 15 seconds" else "Connecting for service discovery", operation = operation, storageReady = true)
        // Atomic entry guarantees finally releases the lease even if cancellation
        // arrives before the dispatcher first runs this coroutine.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            val accumulator = BleAccumulator()
            val started = System.currentTimeMillis()
            val pending = BleSessionRecord(id, operation, "running", started, null, address, stage, "", "{}")
            var began = false
            var status = "completed"
            var detail = ""
            var snapshot: GattSnapshot? = null
            try {
                withContext(NonCancellable) { store.begin(pending) }; began = true
                ensureActive()
                if (operation == "scan") {
                    withTimeoutOrNull(scanMillis) {
                        transport.scan { observation ->
                            if (accumulator.add(observation)) scope.launch {
                                if (activeId == id) mutable.value = mutable.value.copy(observations = accumulator.snapshot())
                            }
                        }
                    }
                    detail = "Discovery window finished; ${accumulator.snapshot().size} observed addresses"
                } else {
                    snapshot = withTimeout(inspectionMillis) { transport.inspect(requireNotNull(address)) }
                    detail = "Service metadata saved; authorization and link encryption were not tested"
                }
            } catch (error: TimeoutCancellationException) {
                status = "timed_out"; detail = "Service discovery timed out; connection closed and not retried"
            } catch (error: CancellationException) {
                status = "cancelled"; detail = "${error.message?.take(200) ?: "Stopped"}; partial observations retained; no automatic restart"
            } catch (error: Exception) {
                status = "failed"; detail = error.message?.take(500) ?: "Bluetooth operation failed"
            } finally {
                val observations = accumulator.close()
                withContext(NonCancellable) {
                    try {
                        if (began) {
                            val result = JSONObject().put("schema_version", 1)
                                .put("observations", JSONArray(observations.map { it.json() }))
                                .put("dropped_new_addresses", accumulator.dropped)
                                .put("replayed", false)
                            snapshot?.let { result.put("snapshot", it.json()) }
                            store.finish(pending.copy(status = status, endedAt = System.currentTimeMillis(), detail = detail, resultJson = result.toString()), observations)
                        }
                    } catch (error: Exception) {
                        detail = "Radio operation ended; saving evidence failed: ${error.message}. Saved running records will be marked interrupted on next app start."
                    } finally {
                        lease.release(id)
                        activeId = null
                        mutable.value = BluetoothUiState(false, detail.ifEmpty { "Operation could not be recorded" }, observations, storageReady = true)
                        job = null
                    }
                }
            }
        }
    }
}
