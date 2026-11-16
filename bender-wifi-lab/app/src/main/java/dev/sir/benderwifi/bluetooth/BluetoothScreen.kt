package dev.sir.benderwifi.bluetooth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private val lime = Color(0xFFC6F35B)
private val muted = Color(0xFFAAB6AC)
private val charcoal = Color(0xFF111512)

@Composable
fun BluetoothScreen(
    store: BluetoothStore,
    coordinator: BluetoothCoordinator,
    access: BluetoothAccess,
    externalMessage: String? = null,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDismissMessage: () -> Unit = {}
) {
    val state by coordinator.state.collectAsState()
    val devices by store.devices.collectAsState(initial = emptyList())
    val sessions by store.sessions.collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableStateOf("Devices") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<String?>(null) }
    BackHandler(selected != null) { selected = null }
    MaterialTheme(colorScheme = darkColorScheme(primary = lime, background = charcoal, surface = Color(0xFF1D241F), onSurface = Color(0xFFECF2ED))) {
        Scaffold(containerColor = charcoal, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF1D241F))) {
                listOf("Devices", "History", "Research").forEach { name ->
                    TextButton(onClick = { tab = name; selected = null }, modifier = Modifier.weight(1f)) {
                        Text(name, color = if (tab == name) lime else muted)
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("BENDER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 23.sp)
                        Text("BLUETOOTH LAB · PREVIEW", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = muted)
                    }
                    TextButton(onClick = onBack) { Text("Wi-Fi") }
                }
                BtCard {
                    Text(state.phase, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (state.busy && state.operation != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { coordinator.cancel() }) { Text("Stop & save") }
                    } else Text(access.message, fontSize = 12.sp, color = muted)
                }
                Spacer(Modifier.height(10.dp))
                if (selected != null) {
                    val address = selected!!
                    DeviceDossier(address, devices.firstOrNull { it.address == address }, store, state, access,
                        onInspect = { stage -> coordinator.inspect(address, stage) }, onBack = { selected = null },
                        onMessage = { message = it }, onRaw = { raw = it })
                } else when (tab) {
                    "Devices" -> {
                        val combined = remember(devices, state.observations) {
                            val map = devices.associateBy { it.address }.toMutableMap()
                            state.observations.forEach { value ->
                                map[value.address] = BleDeviceRecord(value.address, value.name ?: map[value.address]?.name,
                                    map[value.address]?.firstSeen ?: value.observedAt, value.observedAt, value.rssi, value.json().toString())
                            }
                            map.values.sortedByDescending { it.lastSeen }
                        }
                        val filtered = combined.filter { query.isBlank() || it.address.contains(query, true) || it.name.orEmpty().contains(query, true) }
                        LazyColumn(Modifier.weight(1f).testTag("ble-device-list"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            item {
                                BtCard {
                                    Text("Discover nearby BLE devices", fontWeight = FontWeight.Bold)
                                    Text("15-second Android scan. Save observations locally, then select your bulb or TV. Devices may advertise only during setup.", fontSize = 13.sp)
                                    when {
                                        !access.supported -> Text("BLE hardware is unavailable. Saved work and research remain accessible.", color = muted)
                                        !access.permissions -> {
                                            Button(onClick = onPermissions, enabled = !state.busy) { Text("Grant Bluetooth permission") }
                                            TextButton(onClick = onSettings) { Text("Open Android settings") }
                                        }
                                        !access.enabled || !access.locationReady -> Button(onClick = onSettings, enabled = !state.busy) { Text("Open Android settings") }
                                        else -> Button(onClick = coordinator::scan, enabled = !state.busy && state.storageReady) { Text("Discover BLE · 15 seconds") }
                                    }
                                    Text("Operations stop when you leave this screen. Discovery can transmit scan requests. Android may filter some beacon types; this is not a general packet sniffer.", fontSize = 11.sp, color = muted)
                                }
                            }
                            item { OutlinedTextField(query, { query = it }, label = { Text("Search name or observed address") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                            item { Text("${combined.size} saved or current observed addresses", color = muted, fontSize = 12.sp) }
                            if (filtered.isEmpty()) item {
                                BtCard { Text(if (combined.isEmpty()) "No Bluetooth observations yet" else "No matching observations", fontWeight = FontWeight.Bold)
                                    Text("No sample devices are mixed into your inventory. Root and the Wi-Fi runtime are not required.", fontSize = 13.sp, color = muted) }
                            }
                            items(filtered, key = { it.address }) { device ->
                                BtCard(Modifier.clickable { selected = device.address }.testTag("ble-device-${device.address}")) {
                                    Text(device.name ?: "Unnamed BLE device", fontWeight = FontWeight.Bold)
                                    Text(device.address, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    Text("Last seen ${btTime(device.lastSeen)} · ${device.rssi?.let { "$it dBm" } ?: "RSSI unknown"}", fontSize = 12.sp, color = muted)
                                    Text("Observed address · physical identity unverified", fontSize = 11.sp, color = muted)
                                }
                            }
                        }
                    }
                    "History" -> LazyColumn(Modifier.weight(1f).testTag("ble-history-list"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        item {
                            BtCard {
                                Text("Saved Bluetooth work", fontWeight = FontWeight.Bold)
                                Text("Latest 200 operations. Cancelled and failed sessions remain visible. Export includes up to 1,000 operations within 8 MiB and reports any limit.", fontSize = 12.sp, color = muted)
                                OutlinedButton(onClick = onExport, enabled = !state.busy) { Text("Export metadata") }
                                Text("Names, addresses, raw advertisements and notes are omitted. UUIDs and timestamps can still be identifying.", fontSize = 11.sp, color = muted)
                            }
                        }
                        if (sessions.isEmpty()) item { BtCard { Text("No saved Bluetooth operations") } }
                        items(sessions, key = { it.id }) { session ->
                            BtCard {
                                Text("${session.operation} · ${session.status}", fontWeight = FontWeight.Bold)
                                Text(btTime(session.startedAt), fontSize = 12.sp, color = muted)
                                if (session.address != null) TextButton(onClick = { selected = session.address }) { Text(session.address) }
                                Text(session.detail, fontSize = 12.sp)
                                if (session.operation == "scan") {
                                    val result = remember(session.resultJson) { runCatching { JSONObject(session.resultJson) }.getOrDefault(JSONObject()) }
                                    Text("${result.optJSONArray("observations")?.length() ?: 0} addresses · ${result.optInt("dropped_new_addresses")} additional observations dropped at capacity", color = muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    else -> ResearchCatalog(Modifier.weight(1f))
                }
            }
        }
        val visibleMessage = message ?: externalMessage
        if (visibleMessage != null) AlertDialog(onDismissRequest = { message = null; onDismissMessage() },
            title = { Text("Bluetooth Lab") }, text = { SelectionContainer { Text(visibleMessage, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { message = null; onDismissMessage() }) { Text("OK") } })
        if (raw != null) AlertDialog(onDismissRequest = { raw = null }, title = { Text("Private local evidence") },
            text = { SelectionContainer { Text(raw!!, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), fontSize = 11.sp, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { raw = null }) { Text("Close") } })
    }
}

@Composable private fun DeviceDossier(address: String, device: BleDeviceRecord?, store: BluetoothStore,
    state: BluetoothUiState, access: BluetoothAccess, onInspect: (String) -> Unit, onBack: () -> Unit,
    onMessage: (String) -> Unit, onRaw: (String) -> Unit) {
    val inspectionFlow = remember(address) { store.inspections(address) }
    val noteFlow = remember(address) { store.note(address) }
    val encounterFlow = remember(address) { store.encounters(address) }
    val inspections by inspectionFlow.collectAsState(initial = emptyList())
    val savedNote by noteFlow.collectAsState(initial = null)
    val encounters by encounterFlow.collectAsState(initial = emptyList())
    var note by rememberSaveable(address) { mutableStateOf("") }
    var noteDirty by remember(address) { mutableStateOf(false) }
    LaunchedEffect(savedNote) { if (!noteDirty) note = savedNote?.text.orEmpty() }
    var stage by rememberSaveable(address) { mutableStateOf("Unspecified") }
    var stageOpen by remember { mutableStateOf(false) }
    var baseline by rememberSaveable(address) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().testTag("ble-dossier-list"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { TextButton(onClick = onBack) { Text("Back to devices") } }
        item { BtCard {
            Text(device?.name ?: "Observed BLE device", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            SelectionContainer { Text(address, fontFamily = FontFamily.Monospace) }
            Text("Addresses can rotate or be spoofed. Matching names are never merged automatically.", fontSize = 12.sp, color = muted)
            device?.let { value ->
                Text("First ${btTime(value.firstSeen)}\nLast ${btTime(value.lastSeen)}", fontSize = 12.sp)
                TextButton(onClick = { onRaw(JSONObject(value.advertisementJson).toString(2)) }) { Text("View advertisement evidence") }
            }
        } }
        item { BtCard {
            Text("Inspect services", fontWeight = FontWeight.Bold)
            Text("Connect to this address, discover GATT service metadata, and disconnect within 20 seconds. No characteristic values are read or written. The Android stack may use cached metadata or show a pairing prompt.", fontSize = 12.sp)
            Box {
                OutlinedButton(onClick = { stageOpen = true }, enabled = !state.busy) { Text("Stage: $stage") }
                DropdownMenu(stageOpen, { stageOpen = false }) { BluetoothEvidence.stages.forEach { value ->
                    DropdownMenuItem(text = { Text(value) }, onClick = { stage = value; stageOpen = false })
                } }
            }
            Text("Stage is your label, not proof that pairing or ownership changed.", fontSize = 11.sp, color = muted)
            Button(onClick = { onInspect(stage) }, enabled = !state.busy && state.storageReady && access.ready) { Text("Connect & inspect") }
            if (!access.ready) Text(access.message, color = muted, fontSize = 12.sp)
        } }
        item { BtCard {
            Text("Device notes", fontWeight = FontWeight.Bold)
            OutlinedTextField(note, { if (it.length <= 10000) { note = it; noteDirty = true } }, modifier = Modifier.fillMaxWidth(), label = { Text("Model, firmware and observations") }, minLines = 2)
            TextButton(onClick = {
                val submitted = note
                scope.launch {
                    runCatching { store.saveNote(address, submitted) }.onSuccess {
                        if (note == submitted) { noteDirty = false; onMessage("Device notes saved") }
                        else onMessage("Earlier text saved; your newer edits are still unsaved")
                    }.onFailure { onMessage("Could not save notes: ${it.message}") }
                }
            }) { Text("Save device notes") }
        } }
        item { Text("Saved inspections · ${inspections.size}", fontWeight = FontWeight.Bold) }
        if (inspections.isEmpty()) item { BtCard { Text("No service inspection yet. Capability is not a vulnerability finding.", fontSize = 12.sp) } }
        items(inspections, key = { it.id }) { record ->
            val snapshot = remember(record.resultJson) { runCatching { BluetoothEvidence.snapshot(JSONObject(record.resultJson).getJSONObject("snapshot")) }.getOrNull() }
            BtCard {
                Text("${record.stage} · ${record.status}", fontWeight = FontWeight.Bold)
                Text(btTime(record.startedAt), fontSize = 12.sp, color = muted)
                Text(record.detail, fontSize = 12.sp)
                if (snapshot != null) {
                    Text("Android bond state: ${snapshot.bondAtStart} → ${snapshot.bondAtEnd}", fontSize = 12.sp)
                    Text("Authorization untested · link encryption not observed · cache possible", fontSize = 11.sp, color = muted)
                    TextButton(onClick = { baseline = record.id }) { Text(if (baseline == record.id) "Selected baseline" else "Use as baseline") }
                    val base = inspections.firstOrNull { it.id == baseline }
                    if (base != null && base.id != record.id) OutlinedButton(onClick = {
                        runCatching { BluetoothEvidence.compare(BluetoothEvidence.snapshot(JSONObject(base.resultJson).getJSONObject("snapshot")), snapshot) }
                            .onSuccess { onMessage("Compared with ${base.stage} (${btTime(base.startedAt)}):\n\n${it.joinToString("\n")}\n\nMetadata differences alone are not an authorization finding.") }
                            .onFailure { onMessage("Comparison unavailable: ${it.message}") }
                    }) { Text("Compare with baseline") }
                    Text("Services: ${snapshot.services.size} · characteristics: ${snapshot.services.sumOf { it.characteristics.size }}", fontSize = 12.sp)
                    var expanded by remember(record.id) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide service metadata" else "Show service metadata") }
                    if (expanded) snapshot.services.forEach { service ->
                        Text("${service.uuid} #${service.instance}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        service.characteristics.forEach { char ->
                            Text("${char.uuid}\n${char.propertyLabels()} · ${char.descriptors.size} descriptor(s)", fontSize = 11.sp, color = muted)
                        }
                    }
                }
            }
        }
        item { Text("Recent encounters · ${encounters.size}", fontWeight = FontWeight.Bold) }
        items(encounters, key = { "encounter-${it.sessionId}" }) { encounter ->
            BtCard {
                Text(btTime(encounter.observedAt), fontWeight = FontWeight.Bold)
                val observed = remember(encounter.observationJson) { JSONObject(encounter.observationJson) }
                Text("RSSI ${if (observed.isNull("rssi_dbm")) "unknown" else "${observed.getInt("rssi_dbm")} dBm"} · Android bond ${observed.optString("android_bond_state", "unknown")}", fontSize = 12.sp)
                TextButton(onClick = { onRaw(observed.toString(2)) }) { Text("View encounter evidence") }
            }
        }
    }
}

@Composable private fun ResearchCatalog(modifier: Modifier) {
    val entries = listOf(
        Triple("BLE discovery and service comparison", "Built · handset validation pending", "Android APIs, bounded sessions, saved metadata and comparisons. This preview does not establish authorization, encryption or a device vulnerability."),
        Triple("Pairing and authorization", "Research · model and firmware needed", "Compare a documented harmless operation before and after pairing. Read/write properties and an Android bond do not prove that the device enforces access control."),
        Triple("Bluetooth-assisted Wi-Fi provisioning", "Research · device protocol needed", "Test whether an already claimed device protects Wi-Fi setup and ownership changes. Bluetooth access alone does not demonstrate LAN access or password disclosure."),
        Triple("Ownership and restart behavior", "Research · controlled state changes needed", "Compare before/after pairing, restart and ownership transfer. Verify old owners actually lose control. Preserve controls and restoration evidence."),
        Triple("Firmware updates and CVEs", "Research · exact implementation needed", "Record model, firmware and update settings; match vendor advisories and backports. Device age and Bluetooth specification version alone do not establish vulnerability."),
        Triple("Classic Bluetooth and paired keyboard", "Not implemented", "A separate profile from BLE/GATT. TV HID support and pairing must be established. No pairing bypass or keyboard injection is supplied."),
        Triple("Raw controller research", "Unresolved on this handset", "Prior phone inspection found no Linux HCI controller despite root opening an HCI socket. Generic BlueZ tooling is not yet a proven path to this internal radio.")
    )
    LazyColumn(modifier.testTag("ble-research-list"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("Research catalog", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        items(entries) { (title, status, body) -> BtCard {
            Text(title, fontWeight = FontWeight.Bold)
            Text(status, color = lime, fontSize = 12.sp)
            Text(body, fontSize = 13.sp)
        } }
    }
}

@Composable private fun BtCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Color(0xFF1D241F)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
private fun btTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
