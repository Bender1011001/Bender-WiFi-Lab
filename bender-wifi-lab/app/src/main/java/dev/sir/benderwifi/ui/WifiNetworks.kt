package dev.sir.benderwifi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sir.benderwifi.data.ClientEntity
import dev.sir.benderwifi.data.NetworkEntity
import dev.sir.benderwifi.data.ScanInventory
import dev.sir.benderwifi.data.advertisedStationCount

/** Navigation and observed inventory only. Opening a target never runs a radio operation. */
@Composable
internal fun WifiNetworks(
    networks:List<NetworkEntity>, clients:List<ClientEntity>,
    selected:NetworkEntity?, device:ClientEntity?, catalog:List<CatalogAction>,
    busy:Boolean, recovery:Boolean,
    onNetwork:(String?)->Unit, onDevice:(String?)->Unit,
    onScan:()->Unit, onTools:()->Unit, onCapture:()->Unit, onHandshake:()->Unit,
    onDeauth:()->Unit, onEvidence:(String)->Unit, onUnavailable:(CatalogAction)->Unit,
    savedWork:@Composable (String)->Unit,onWps:()->Unit={},
    inventoryScope:String="Latest scan",onInventoryScope:(String)->Unit={},latestScan:ScanInventory=ScanInventory()
) {
    val pages=rememberSaveableStateHolder()
    BackHandler(enabled=device!=null||selected!=null) {
        if(device!=null)onDevice(null) else onNetwork(null)
    }
    when {
        device!=null -> DevicePage(device,selected,catalog,busy,recovery,
            onBack={onDevice(null)},onHandshake=onHandshake,onDeauth=onDeauth,
            onEvidence=onEvidence,onUnavailable=onUnavailable)
        selected!=null -> pages.SaveableStateProvider(selected.id) {NetworkPage(selected,clients.filter {it.apId==selected.id},catalog,
            busy,recovery,onBack={onNetwork(null)},onDevice=onDevice,
            onCapture=onCapture,onHandshake=onHandshake,onEvidence=onEvidence,
            onUnavailable=onUnavailable,savedWork=savedWork,onWps=onWps,inventoryScope=inventoryScope)}
        else -> pages.SaveableStateProvider("index:$inventoryScope") {NetworkIndex(networks,clients,busy,recovery,onNetwork,onDevice,onScan,onTools,inventoryScope,onInventoryScope,latestScan)}
    }
}

@Composable
private fun NetworkIndex(
    networks:List<NetworkEntity>,clients:List<ClientEntity>,busy:Boolean,recovery:Boolean,
    onNetwork:(String?)->Unit,onDevice:(String?)->Unit,onScan:()->Unit,onTools:()->Unit,
    inventoryScope:String,onInventoryScope:(String)->Unit,latestScan:ScanInventory
) {
    var query by rememberSaveable {mutableStateOf("")}
    var band by rememberSaveable {mutableStateOf("All bands")}
    var order by rememberSaveable {mutableStateOf("Last seen")}
    var showUnlinked by rememberSaveable {mutableStateOf(false)}
    val counts=remember(clients){clients.groupingBy {it.apId}.eachCount()}
    val ids=remember(networks){networks.map {it.id}.toSet()}
    val unlinked=remember(clients,ids){clients.filter {it.apId !in ids}}
    val matching=networks.filter {
        (query.isBlank()||"${it.ssidDisplay} ${it.bssid} ${it.security}".contains(query,true))&&
            (band=="All bands"||bandFor(it.frequencyMhz)==band)
    }
    val displayed=when(order){
        "Devices"->matching.sortedByDescending {counts[it.id]?:0}
        "Signal"->matching.sortedByDescending {it.signalDbm?:Int.MIN_VALUE}
        else->matching.sortedByDescending {it.lastSeen}
    }
    LazyColumn(Modifier.testTag("network-list"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {ChoiceRow(listOf("Latest scan","Saved history"),inventoryScope,onInventoryScope)}
        item {
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=!busy&&!recovery,onClick=onScan,modifier=Modifier.fillMaxWidth()) {Text("Scan networks & devices")}
                Text("120-second survey · available 2.4 / 5 / 6 GHz channels. Results are saved after capture and analysis.",fontSize=12.sp)
                Text("Scanning temporarily interrupts this phone’s Wi-Fi connection. Use Finish & save to end early.",fontSize=12.sp)
                TextButton(onClick=onTools) {Text("Radio setup & tools")}
            }}
        }
        item {Text("${networks.size} network${if(networks.size==1)"" else "s"} · ${clients.size} ${if(inventoryScope=="Latest scan")"observed" else "saved"} device address${if(clients.size==1)"" else "es"}",fontWeight=FontWeight.Bold)}
        item {Text(if(inventoryScope=="Saved history")"All saved observations, including older scans and imports. These devices may no longer be nearby." else when {
            latestScan.attempt==null -> "No live scan yet. Run a scan, or open Saved history for earlier captures and imports."
            latestScan.session==null && latestScan.attempt.state=="running" -> "Scanning… results appear after capture and analysis. Earlier observations remain in Saved history."
            latestScan.session==null -> "The latest scan has no available results. Check its record in Evidence. Earlier observations remain in Saved history."
            else -> "${if(latestScan.isSurvey)"All-band scan" else "Selected-channel capture"}${if(latestScan.attempt.state=="completed")"" else " (partial)"} started ${stamp(latestScan.attempt.createdAt/1000.0)}. Only observations from this capture; scan again to refresh."
        },fontSize=12.sp)}
        item {Text("AP totals come from captured beacons when available. Identified addresses come from observed traffic; quiet devices may be missed. Each BSSID stays separate, even with the same network name.",fontSize=12.sp)}
        item {OutlinedTextField(query,{query=it},label={Text("Search networks, BSSID or security")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item {ChoiceRow(listOf("All bands","2.4 GHz","5 GHz","6 GHz"),band){band=it}}
        item {ChoiceRow(listOf("Last seen","Signal","Devices"),order){order=it}}
        if(displayed.isEmpty())item {Text(if(networks.isNotEmpty())"No matching networks." else if(inventoryScope=="Saved history")"No saved networks yet." else if(latestScan.session!=null)"No networks observed in this scan." else "No scan results to display.")}
        items(displayed,key={it.id}) {n ->
            NetworkSummary(n,counts[n.id]?:0,onClick={onNetwork(n.id)})
        }
        item {
            OutlinedButton(onClick={showUnlinked=!showUnlinked},modifier=Modifier.fillMaxWidth()) {
                Text("${if(showUnlinked)"Hide" else "Show"} unlinked devices (${unlinked.size})")
            }
        }
        if(showUnlinked) {
            item {Text("These addresses have no observed relationship to a saved AP. They are not assigned to a network by name or signal strength.",fontSize=12.sp)}
            items(unlinked,key={"unlinked:${it.id}"}) {c ->DeviceRow(c){onDevice(c.id)}}
        }
        item {Spacer(Modifier.height(16.dp))}
    }
}

@Composable
private fun NetworkPage(
    network:NetworkEntity,devices:List<ClientEntity>,catalog:List<CatalogAction>,busy:Boolean,recovery:Boolean,
    onBack:()->Unit,onDevice:(String?)->Unit,onCapture:()->Unit,onHandshake:()->Unit,
    onEvidence:(String)->Unit,onUnavailable:(CatalogAction)->Unit,savedWork:@Composable (String)->Unit,onWps:()->Unit,inventoryScope:String
) {
    var section by rememberSaveable(network.id){mutableStateOf("Actions")}
    Column {
        TextButton(onClick=onBack){Text("‹ All networks")}
        Text(network.ssidDisplay.ifBlank {"Hidden SSID"},fontWeight=FontWeight.Bold,fontSize=20.sp)
        Text("${network.bssid} · ${bandFor(network.frequencyMhz)} · Ch ${network.channel?:"?"}",fontFamily=FontFamily.Monospace,fontSize=11.sp)
        ChoiceRow(listOf("Actions","Devices (${devices.size})","Saved work","Details"),
            if(section=="Devices")"Devices (${devices.size})" else section) {section=if(it.startsWith("Devices ("))"Devices" else it}
        key(network.id,section) {
            LazyColumn(Modifier.testTag("network-detail-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(section) {
                    "Actions" -> {
                        item {TargetAction("Capture handshake","Listen on this network’s channel for authentication traffic. Reconnect a client once listening starts.",!busy&&!recovery&&network.frequencyMhz!=null,onHandshake)}
                        item {TargetAction("Capture packets","120-second capture on this AP’s channel. Finish & save can end it early.",!busy&&!recovery&&network.frequencyMhz!=null,onCapture)}
                        item {TargetAction("Deauth a device","Choose an observed device, then open its directed deauth test.",true){section="Devices"}}
                        item {TargetAction("WPS · Pixie Dust","Collect an exchange on the internal radio or import one; check three common PIN candidates in a separate experimental action. Save verified results in Vault.",!busy,onWps)}
                        item {Text("More network tools",fontWeight=FontWeight.Bold)}
                        items(catalog.filter {"ap" in it.targets&&it.operation==null},key={it.id}) {a->ActionCard(a){onUnavailable(a)}}
                    }
                    "Devices" -> {
                        item {Text("${devices.size} identified address${if(devices.size==1)"" else "es"} · ${if(inventoryScope=="Latest scan")"this scan" else "latest saved relationships"}",fontWeight=FontWeight.Bold)}
                        item {Text("Select a device to see its actions. Addresses may be randomized; this is not a count of physical devices currently connected.",fontSize=12.sp)}
                        item {Text("Device guesses combine offline vendor prefixes with captured names, models and service clues. Encrypted or quiet traffic may reveal only an address.",fontSize=12.sp)}
                        item {StationCount(network)}
                        item {TargetAction("Look for devices","Listen on this AP’s channel for up to 120 seconds. Quiet devices may need longer to appear.",!busy&&!recovery&&network.frequencyMhz!=null,onCapture)}
                        items(devices.sortedByDescending {it.lastSeen},key={it.id}) {c->DeviceRow(c){onDevice(c.id)}}
                    }
                    "Saved work" -> item {savedWork(network.id)}
                    "Details" -> {
                        item {SecurityDetails(network)}
                        item {Text("Last seen: ${stamp(network.lastSeen)}\nSource: ${network.sessionId}\nAdvertised PMF: ${network.pmfAdvertised}\nNegotiated security and PMF remain unknown without association evidence.",fontSize=12.sp)}
                        item {OutlinedButton(onClick={onEvidence(network.rawJson)}){Text("Raw network evidence")}}
                    }
                }
                item {Spacer(Modifier.height(16.dp))}
            }
        }
    }
}

@Composable
private fun DevicePage(
    device:ClientEntity,network:NetworkEntity?,catalog:List<CatalogAction>,busy:Boolean,recovery:Boolean,
    onBack:()->Unit,onHandshake:()->Unit,onDeauth:()->Unit,onEvidence:(String)->Unit,
    onUnavailable:(CatalogAction)->Unit
) {
    val linked=network!=null&&device.apId==network.id
    val guess=rememberDeviceGuess(device.mac,device.rawJson)
    LazyColumn(Modifier.testTag("device-detail-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=onBack){Text(if(linked)"‹ Network devices" else "‹ All networks")}}
        item {Text("Device actions",fontWeight=FontWeight.Bold,fontSize=20.sp)}
        item {Text(device.mac,fontFamily=FontFamily.Monospace,fontSize=18.sp)}
        item {DeviceIdentityCard(guess)}
        item {Text("${if(linked)"Network: ${network!!.ssidDisplay.ifBlank {"Hidden SSID"}} (${network.bssid})" else "Network relationship unknown"}\nLast seen: ${stamp(device.lastSeen)}\n${device.signalDbm?.let {"Signal: $it dBm"}?:"Signal unknown"}",fontSize=12.sp)}
        if(linked) {
            item {TargetAction("Deauth selected device","One directed request to ${device.mac}. Review preparation and frequency before running.",!busy&&!recovery,onDeauth)}
            item {TargetAction("Capture network handshake","Listens on the AP’s channel. Captures can include other devices on this network.",!busy&&!recovery&&network?.frequencyMhz!=null,onHandshake)}
        } else item {Text("AP-specific actions need an observed AP relationship and channel. Scan or import that evidence first.",fontSize=12.sp)}
        item {OutlinedButton(onClick={onEvidence(device.rawJson)}){Text("Raw device evidence")}}
        item {Text("More device tools",fontWeight=FontWeight.Bold)}
        items(catalog.filter {"client" in it.targets&&it.operation==null},key={it.id}) {a->ActionCard(a){onUnavailable(a)}}
        item {Spacer(Modifier.height(16.dp))}
    }
}

@Composable
private fun ChoiceRow(choices:List<String>,selected:String,onChoice:(String)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        choices.forEach {choice->FilterChip(choice==selected,{onChoice(choice)},label={Text(choice,fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.primary.copy(alpha=0.15f),selectedLabelColor=MaterialTheme.colorScheme.primary))}
    }
}

@Composable
private fun NetworkSummary(network:NetworkEntity,count:Int,onClick:()->Unit) {
    val reported=remember(network.rawJson){network.advertisedStationCount()}
    Card(Modifier.fillMaxWidth().clickable(onClick=onClick).testTag("network:${network.id}"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(network.ssidDisplay.ifBlank {"Hidden SSID"},fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                Column(horizontalAlignment=Alignment.End) {
                    Text(reported?.count?.toString()?:"?",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,fontSize=24.sp)
                    Text("AP total",fontSize=10.sp)
                }
            }
            Text("${network.security} · ${bandFor(network.frequencyMhz)} · Ch ${network.channel?:"?"}",fontSize=12.sp)
            Text("${if(count==0)"No device addresses identified" else "$count device address${if(count==1)"" else "es"} identified"} · ${network.signalDbm?.let {"$it dBm"}?:"signal unknown"}",fontSize=12.sp)
            StationCount(network)
            Text(network.bssid,fontFamily=FontFamily.Monospace,fontSize=11.sp)
            Text("Seen ${stamp(network.lastSeen)}",fontSize=11.sp)
        }
    }
}

@Composable
private fun StationCount(network:NetworkEntity) {
    val reported=remember(network.rawJson){network.advertisedStationCount()}
    Text(reported?.let {"AP reported ${it.count} connected stations at ${stamp(it.time)}"}
        ?:"Connected-device total unknown · no AP count captured",fontSize=11.sp)
}

@Composable
private fun DeviceRow(device:ClientEntity,onClick:()->Unit) {
    val guess=rememberDeviceGuess(device.mac,device.rawJson)
    Card(Modifier.fillMaxWidth().clickable(onClick=onClick).testTag("device:${device.id}"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(guess.title,fontWeight=FontWeight.Bold)
            Text("${guess.type} · ${guess.confidence}",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            Text(device.mac,fontFamily=FontFamily.Monospace,fontSize=12.sp)
            Text("${device.signalDbm?.let {"$it dBm"}?:"Signal unknown"} · Seen ${stamp(device.lastSeen)}",fontSize=12.sp)
            Text("View device actions ›",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TargetAction(title:String,description:String,enabled:Boolean,onClick:()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(description,fontSize=12.sp)
        Button(enabled=enabled,onClick=onClick,modifier=Modifier.fillMaxWidth()){Text(title)}
    }}
}
