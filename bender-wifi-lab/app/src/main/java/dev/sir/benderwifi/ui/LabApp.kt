package dev.sir.benderwifi.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.security.keystore.UserNotAuthenticatedException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sir.benderwifi.data.*
import dev.sir.benderwifi.radio.RadioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val Lime=Color(0xFFC6F35B)
private val Charcoal=Color(0xFF111512)
private val Surface=Color(0xFF1D241F)
private val Muted=Color(0xFFAAB6AC)
internal data class CatalogAction(val id:String,val title:String,val targets:List<String>,val status:String,val description:String,val blocker:String,val operation:String?)

@Composable
fun LabApp(repository:LabRepository?=null) {
    val context=LocalContext.current
    val repo=remember(repository) {repository?:LabRepository.get(context)}
    val networks by repo.networks.collectAsState(initial=emptyList())
    val clients by repo.clients.collectAsState(initial=emptyList())
    val experiments by repo.experiments.collectAsState(initial=emptyList())
    val credentials by repo.credentials.collectAsState(initial=emptyList())
    val sessions by repo.sessions.collectAsState(initial=emptyList())
    val latestScan by repo.latestScan.collectAsState(initial=dev.sir.benderwifi.data.ScanInventory())
    val radio by RadioController.state.collectAsState()
    val runtimeReport by RadioController.runtimeState.collectAsState()
    var identityNow by remember {mutableStateOf(System.currentTimeMillis()/1000.0)}
    LaunchedEffect(radio.identityReport){while(true){identityNow=System.currentTimeMillis()/1000.0;kotlinx.coroutines.delay(5_000)}}
    val identity=identityPreflight(radio.identityReport,identityNow)
    val scope=rememberCoroutineScope()
    var tab by rememberSaveable {mutableStateOf("Networks")}
    var apId by rememberSaveable {mutableStateOf<String?>(null)}
    var clientId by rememberSaveable {mutableStateOf<String?>(null)}
    var inventoryScope by rememberSaveable {mutableStateOf("Latest scan")}
    var message by remember {mutableStateOf<String?>(null)}
    var raw by remember {mutableStateOf<String?>(null)}
    var deauthConfirm by remember {mutableStateOf(false)}
    var handshakeConfirm by remember {mutableStateOf(false)}
    var exportSession by rememberSaveable {mutableStateOf<String?>(null)}
    var deleteCapture by remember {mutableStateOf<String?>(null)}
    var cleanupRuntime by remember {mutableStateOf(false)}
    var revealed by remember {mutableStateOf<String?>(null)}
    val lifecycle=LocalLifecycleOwner.current
    val reveals=remember(lifecycle) {VaultRevealController({lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)},{revealed=it})}
    var newSecret by remember {mutableStateOf(false)}
    var pendingAuth by remember {mutableStateOf<(() -> Unit)?>(null)}
    val visibleNetworks=if(inventoryScope=="Latest scan")latestScan.networks else networks
    val visibleClients=if(inventoryScope=="Latest scan")latestScan.clients else clients
    val selected=visibleNetworks.firstOrNull {it.id==apId}
    val selectedClient=visibleClients.firstOrNull {it.id==clientId}
    val catalog=remember {loadCatalog(context)}
    val auth=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val action=pendingAuth;pendingAuth=null
        if(result.resultCode==Activity.RESULT_OK)action?.invoke() else message="Unlock cancelled. Your existing encrypted entry is unchanged."
    }
    fun authenticate(action:()->Unit) {
        val keyguard=context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION") val intent=keyguard.createConfirmDeviceCredentialIntent("Upgrade saved credential","Unlock once to read an entry saved by an earlier version. Future reveals use your phone unlock.")
        if(intent==null)message="This older entry needs its original device authentication. Its encrypted data has been preserved." else {pendingAuth=action;auth.launch(intent)}
    }
    fun revealCredential(id:String) {
        scope.launch {
            runCatching {reveals.reveal {repo.revealCredential(id)}}.onFailure {error->
                if(error is UserNotAuthenticatedException)authenticate {revealCredential(id)}
                else message="Unable to reveal credential. Unlock your phone and retry. If the original key is unavailable, the encrypted entry is preserved."
            }
        }
    }
    DisposableEffect(lifecycle,reveals){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)reveals.lock()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {reveals.lock();pendingAuth=null;lifecycle.lifecycle.removeObserver(observer)}
    }
    var strictPrivacy by rememberSaveable {mutableStateOf(false)}
    fun run(operation:String,request:JSONObject=JSONObject()) {
        if(operation in setOf("capture","lab"))request.put("privacy_profile",if(strictPrivacy)"strict" else "wifi")
        runCatching { RadioController.start(operation,request) }.onFailure {message=it.message?:"Operation could not start"}
    }
    fun openSettings(action:String) {
        runCatching {context.startActivity(Intent(action))}.onFailure {message="This settings page is unavailable. Open Android Settings manually."}
    }
    val importJson=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)scope.launch {
            runCatching {
                val text=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use {input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=10_000_000){"File exceeds 10 MB"};output.write(buffer,0,count)}
                    output.toString("UTF-8")
                }?:error("Unable to read selected file")}
                repo.importObservations(text,"imported:${UUID.randomUUID()}")
            }.onSuccess {message="Observation evidence imported and saved by network."}.onFailure {message=it.message?:"Import failed"}
        }
    }
    val importPcap=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runCatching { RadioController.startAnalysis(uri) }.onFailure {message=it.message?:"PCAP analysis could not start"}
    }
    val exportPcap=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap")){uri->
        val origin=exportSession;exportSession=null
        if(uri!=null&&origin!=null)scope.launch {
            runCatching {withContext(Dispatchers.IO){
                val source=RadioController.captureArtifact(origin)?:error("Capture is unavailable or still running")
                context.contentResolver.openOutputStream(uri)?.use {output->source.inputStream().use {it.copyTo(output)}}?:error("Cannot write the selected document")
            }}.onSuccess {message="Raw capture exported. It includes network identifiers and authentication traffic."}.onFailure {message=it.message?:"Capture export failed"}
        }
    }
    val exportJson=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)scope.launch {
            runCatching {val text=repo.exportEvidence();withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(text)}?:error("Unable to write export")}}
                .onSuccess {message="Redacted evidence exported; vault records excluded."}.onFailure {message=it.message?:"Export failed"}
        }
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=Lime,onPrimary=Charcoal,background=Charcoal,surface=Surface,onSurface=Color(0xFFE9EFE9),secondary=Color(0xFFE9D66B)),typography=Typography()) {
        Scaffold(containerColor=Charcoal,bottomBar={
            Surface(color=Surface){Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=6.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceEvenly){
                listOf("Networks","Tools","Vault","Evidence").forEach {name->
                    TextButton(modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=2.dp,vertical=8.dp),onClick={tab=name;reveals.lock()}){Text(name,fontSize=11.sp,maxLines=1,color=if(tab==name)Lime else Muted,fontWeight=if(tab==name)FontWeight.Bold else FontWeight.Normal)}
                }
            }}
        }){padding->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically){
                    BenderMark(Modifier.size(48.dp))
                    Column(Modifier.padding(start=12.dp).weight(1f)){Text("BENDER",fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Black,fontSize=23.sp,letterSpacing=3.sp);Text("WI-FI LAB  /  INTERNAL RADIO",fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Muted)}
                    TextButton(onClick={context.startActivity(Intent(context,dev.sir.benderwifi.bluetooth.BluetoothActivity::class.java))}){Text("Bluetooth",color=Lime,fontSize=11.sp)}
                }
                Surface(shape=RoundedCornerShape(12.dp),color=Surface,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) { Row(verticalAlignment=Alignment.CenterVertically){
                        Box(Modifier.size(7.dp).background(if(radio.recoveryRequired)Color(0xFFFFB86B) else Lime))
                        Column(Modifier.weight(1f).padding(start=10.dp)){Text(radio.phase,fontSize=13.sp);Text(if(radio.recoveryRequired)"Recovery required before another radio operation" else "Experimental radio · handset qualification pending",fontSize=11.sp,color=Muted)}
                    }
                        CaptureControls(radio.busy,radio.operation=="capture"&&radio.captureReady,onFinish={RadioController.finishCapture()},onCancel={RadioController.cancel()})
                    }
                }
                Spacer(Modifier.height(10.dp))
                if(!radio.recoveryChecked)RecoveryStatusBanner(!radio.busy){run("recovery_status")}
                if(radio.privacyPending) {
                    Surface(color=Color(0xFF42391E),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("Lab connectivity changes need restoration",fontWeight=FontWeight.Bold,fontSize=13.sp)
                            Text("Calls, data, Bluetooth or Internet access may still be interrupted.",fontSize=12.sp)
                            TextButton(enabled=!radio.busy,onClick={run("privacy_restore")}){Text("Restore normal connectivity")}
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if(tab!="Networks"||(selected==null&&selectedClient==null)) {
                    Text(tab,fontSize=26.sp,fontWeight=FontWeight.Bold)
                    Text(when(tab){"Networks"->"Scan. Choose a network or device. Take action.";"Tools"->"Setup, imports and the full capability catalog.";"Vault"->"Encrypted credentials. Reveal and copy.";else->"Capture origins, experiments and raw observations."},color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=4.dp,bottom=14.dp))
                }
                when(tab){
                    "Networks"->WifiNetworks(visibleNetworks,visibleClients,selected,selectedClient,catalog,radio.busy,radio.recoveryRequired,
                        onNetwork={apId=it;clientId=null},
                        onDevice={id->clientId=id;if(id!=null)apId=visibleClients.firstOrNull {it.id==id}?.apId},
                        onScan={inventoryScope="Latest scan";apId=null;clientId=null;run("capture",JSONObject().put("bands",JSONArray(listOf("2.4","5","6"))).put("duration_seconds",120).put("purpose","survey"))},
                        onTools={tab="Tools"},
                        onCapture={selected?.let {n->run("capture",JSONObject().put("bssid",n.bssid.lowercase()).put("frequency",n.frequencyMhz).put("channel",n.channel).put("duration_seconds",120).put("bands",JSONArray(listOf(runtimeBand(n.frequencyMhz))))) }},
                        onHandshake={handshakeConfirm=true},onDeauth={deauthConfirm=true},onEvidence={raw=it},
                        onUnavailable={a->message="${a.title}\n\n${a.description}\n\n${a.blocker}"},
                        savedWork={networkId->NetworkWorkspace(networkId,repo,onEvidence={raw=it},onExportCapture={origin->exportSession=origin;exportPcap.launch("bender-capture-${System.currentTimeMillis()}.pcap")},onMessage={message=it})},
                        onWps={context.startActivity(Intent(context,dev.sir.benderwifi.wps.WpsActivity::class.java).putExtra("network_id",selected?.id))},
                        inventoryScope=inventoryScope,onInventoryScope={inventoryScope=it;apId=null;clientId=null},latestScan=latestScan)
                    "Tools"->LazyColumn(modifier=Modifier.testTag("workshop-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {OutlinedButton(onClick={importJson.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Import observations JSON")}}
                        item {SetupPanel(runtimeReport,radio.busy,onSetup={run("setup")},onProbe={run("probe")})}
                        item {IdentityPanel(identity,enabled=!radio.busy,privacyPending=radio.privacyPending,onRestore={run("privacy_restore")},onCheck={run("identity")},onWifi={openSettings(Settings.ACTION_WIFI_SETTINGS)},strict=strictPrivacy,onStrict={strictPrivacy=it})}
                        radio.privacyReport?.let {report->item {TextButton(onClick={raw=report}){Text("Preparation / restoration report")}}}
                        item {NotificationAccessPanel()}
                        item {InfoCard("Radio control",listOf("Backend" to "Root / QCA internal radio","Status" to "Experimental until this APK is qualified on handset","Capture analysis" to "Runs after radio restoration when available"))}
                        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(enabled=!radio.busy,onClick={run("probe")}){Text("Probe backend")};OutlinedButton(enabled=!radio.busy,onClick={run("restore")}){Text("Restore Wi-Fi")}}}
                        item {OutlinedButton(onClick={RadioController.reconcile()}){Text("Reconcile session state")}}
                        items(catalog){a->ActionCard(a,enabled=!radio.busy){if(a.status in listOf("unavailable","awaiting_evidence"))message=a.blocker.ifBlank {a.description} else when(workshopDestination(a.operation)){
                            "run"->run(a.operation!!)
                            "radar"->{apId=null;clientId=null;tab="Networks"}
                            "dossiers"->{tab="Networks";message="Select a network, open Devices, then choose a device."}
                            "import"->importPcap.launch(arrayOf("application/vnd.tcpdump.pcap","application/x-pcapng","application/octet-stream"))
                            "wps"->context.startActivity(Intent(context,dev.sir.benderwifi.wps.WpsActivity::class.java).putExtra("network_id",selected?.id))
                            else->message=a.blocker.ifBlank {a.description}
                        }}}
                        item {EmptyCard("Offline PCAP analysis","Analyze a PCAP or PCAPNG from storage using the bundled engine. The runtime decoder and root/chroot prerequisites must be present; JSON import works without them.")}
                        item {OutlinedButton(enabled=!radio.busy,onClick={importPcap.launch(arrayOf("application/vnd.tcpdump.pcap","application/x-pcapng","application/octet-stream"))}){Text("Import PCAP / PCAPNG")}}
                        item {Spacer(Modifier.height(16.dp))}
                    }
                    "Vault"->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {Button(onClick={newSecret=true}){Text("Add credential")}}
                        item {Text("Secrets stay encrypted with Android Keystore. Tap Reveal to view or copy; the value hides when you leave the app. A phone screen lock protects access. Older entries may ask for one unlock during upgrade. Vault data stays out of ordinary evidence exports.",color=Muted,fontSize=12.sp)}
                        if(credentials.isEmpty())item {EmptyCard("Vault is empty","Save credentials from your own network manually. Runtime credential ingestion uses the same encrypted boundary and preserves its source and verification state.")}
                        items(credentials,key={it.id}){c->Surface(shape=RoundedCornerShape(12.dp),color=Surface){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(c.label,fontWeight=FontWeight.Bold);Text("Source: ${c.source}\nVerification: ${c.verification}",color=Muted,fontSize=12.sp);c.username?.let {Text("Username: $it",fontSize=12.sp)};Row {TextButton(onClick={revealCredential(c.id)}){Text("Reveal")};TextButton(onClick={scope.launch {repo.deleteCredential(c.id)}}){Text("Delete")}}}}}
                    }
                    else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={exportJson.launch("bender-evidence-${System.currentTimeMillis()}.json")}){Text("Export redacted")};OutlinedButton(onClick={importJson.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Import JSON")}}}
                        item {Text("Exports contain selected typed metadata; names, secrets, raw bytes and free text are excluded. Timestamps and technical details can still identify a session. Raw PCAP export is private.",color=Muted,fontSize=12.sp)}
                        item {Text("Raw captures stay in app storage until deleted. Verified runtime duplicates are removed after handoff. Failed handoffs and older versions may leave root copies; clearing app data or uninstalling does not remove those copies.",color=Muted,fontSize=12.sp)}
                        item {OutlinedButton(enabled=!radio.busy,onClick={cleanupRuntime=true}){Text("Clear runtime capture copies")}}
                        if(sessions.isEmpty()&&experiments.isEmpty())item {EmptyCard("No evidence yet","Imported observations and completed runtime sessions will appear here with their origins.")}
                        items(experiments,key={"run:${it.id}"}){e->Column {
                            InfoCard("${e.operation} · ${e.state}",listOf("Session" to e.id,"Recorded" to DateFormat.getDateTimeInstance().format(Date(e.createdAt))),onClick={raw=e.result})
                            SessionCaptureExport(e.id,{exportSession=e.id;exportPcap.launch("bender-capture-${System.currentTimeMillis()}.pcap")})
                            SessionCaptureDelete(e.id,radio.busy){deleteCapture=e.id}
                        }}
                        items(sessions,key={it.id}){s->Column {
                            Text(integritySummary(s.sourceJson),color=Muted,fontSize=12.sp)
                            InfoCard(s.origin,listOf("Session" to s.id,"Imported" to DateFormat.getDateTimeInstance().format(Date(s.importedAt))),onClick={scope.launch {runCatching {repo.getSessionEvidence(s.id)}.onSuccess {raw=it}.onFailure {message="Unable to load session evidence"}}})
                            SessionCaptureExport(s.id,{exportSession=s.id;exportPcap.launch("bender-capture-${System.currentTimeMillis()}.pcap")})
                            SessionCaptureDelete(s.id,radio.busy){deleteCapture=s.id}
                        }}
                        item {Spacer(Modifier.height(16.dp))}
                    }
                }
            }
        }
        deleteCapture?.let {origin->AlertDialog(onDismissRequest={deleteCapture=null},title={Text("Delete raw capture?")},text={Text("Remove this session's raw PCAP, capture CSV and decoder/capture logs from app storage and the selected root runtime. Summaries, notes, credentials and recovery records stay saved. Export any raw evidence you need first.")},dismissButton={TextButton(onClick={deleteCapture=null}){Text("Cancel")}},confirmButton={Button(enabled=!radio.busy,onClick={deleteCapture=null;run("delete_capture",JSONObject().put("capture_session",origin))}){Text("Delete raw capture")}})}
        if(cleanupRuntime)AlertDialog(onDismissRequest={cleanupRuntime=false},title={Text("Clear runtime capture copies?")},text={Text("Delete raw capture files and capture/decoder logs in Bender's session directories in the selected root runtime, including older orphan copies. This may delete the only raw copy left by a failed handoff. App copies, summaries, other tools' directories and recovery records stay saved.")},dismissButton={TextButton(onClick={cleanupRuntime=false}){Text("Cancel")}},confirmButton={Button(enabled=!radio.busy,onClick={cleanupRuntime=false;run("cleanup_captures")}){Text("Clear runtime copies")}})
        message?.let {text->AlertDialog(onDismissRequest={message=null},title={Text("Lab status")},text={Text(text)},confirmButton={TextButton(onClick={message=null}){Text("Close")}})}
        raw?.let {text->AlertDialog(onDismissRequest={raw=null},title={Text("Raw evidence")},text={SelectionContainer {LazyColumn {item {Text(runCatching {JSONObject(text).toString(2)}.getOrDefault(text),fontFamily=FontFamily.Monospace,fontSize=11.sp)}}}},confirmButton={TextButton(onClick={raw=null}){Text("Close")}})}
        revealed?.let {text->CredentialRevealDialog(text,onClose={reveals.lock()},onCopy={
            val clip=ClipData.newPlainText("Bender credential",text).apply {
                description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        })}
        if(newSecret)CredentialDialog(onDismiss={newSecret=false},onSave={label,user,secret->
            newSecret=false
            scope.launch {runCatching {repo.ingestCredential(secret,apId,label,user.ifBlank {null},"manual entry","unverified")}.onSuccess {message="Credential encrypted and saved."}.onFailure {message="Could not save credential. Unlock your phone and retry; existing entries are unchanged."}}
        })
        if(deauthConfirm) {
            val blocker=labBlocker(selected,selectedClient,radio.busy,radio.recoveryRequired)?:if(radio.privacyPending)"Restore the pending privacy session first." else null
            AlertDialog(onDismissRequest={deauthConfirm=false},title={Text("Directed deauth test")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("AP: ${selected?.bssid?:"None"}\nClient: ${selectedClient?.mac?:"None"}")
                Text(if(strictPrivacy)"Strict setup temporarily disables cellular and Bluetooth and blocks IPv4 / IPv6. Cleanup restores saved settings." else "Automatic setup checks the current Wi-Fi MAC and blocks ordinary IPv4 / IPv6 output on wlan0. Cellular and Bluetooth stay as they are. Cleanup restores the saved Wi-Fi state after success, failure or cancellation.",fontSize=12.sp,color=Muted)
                Text("Association identifiers, raw DHCP and firmware traffic can still identify this phone. This does not establish anonymity. If the current MAC is not randomized, the test stops with a report.",fontSize=12.sp,color=Muted)
                Text(blocker?:"One directed request may disconnect the selected client. Saved observations may be out of date; confirm the selected addresses and frequency. Use only where authorized.")
                OutlinedButton(enabled=!radio.busy&&!radio.recoveryRequired&&selected?.frequencyMhz!=null,onClick={selected?.let {n->
                    deauthConfirm=false;run("capture",JSONObject().put("bssid",n.bssid.lowercase()).put("frequency",n.frequencyMhz).put("channel",n.channel).put("duration_seconds",30).put("bands",JSONArray(listOf(runtimeBand(n.frequencyMhz)))))
                }}){Text("Refresh AP / client evidence")}
            }},dismissButton={TextButton(onClick={deauthConfirm=false}){Text("Cancel")}},confirmButton={Button(enabled=blocker==null,onClick={
                val currentBlocker=labBlocker(selected,selectedClient,radio.busy,radio.recoveryRequired)?:if(radio.privacyPending)"Restore the pending privacy session first." else null
                if(currentBlocker!=null)message=currentBlocker else {
                    val n=selected!!;val c=selectedClient!!;deauthConfirm=false
                    run("lab",JSONObject().put("ap",n.bssid.lowercase()).put("bssid",n.bssid.lowercase()).put("client",c.mac.lowercase()).put("frequency",n.frequencyMhz).put("channel",n.channel).put("capture_session",n.sessionId))
                }
            }){Text("Run bounded test")}})
        }
        if(handshakeConfirm)AlertDialog(onDismissRequest={handshakeConfirm=false},title={Text("Capture authentication on this network")},text={Text("Bender will listen on the selected AP's channel for 120 seconds. When the status says Listening, reconnect an owned client to Wi-Fi. Captures and separate handshake exchanges are saved in this network's workspace.\n\nDeauth runs separately because the internal radio currently switches modes between transmission and capture.")},dismissButton={TextButton(onClick={handshakeConfirm=false}){Text("Cancel")}},confirmButton={Button(enabled=selected!=null&&!radio.busy&&!radio.recoveryRequired,onClick={
            selected?.let {n->handshakeConfirm=false;run("capture",JSONObject().put("bssid",n.bssid.lowercase()).put("frequency",n.frequencyMhz).put("channel",n.channel).put("duration_seconds",120).put("purpose","handshake").put("bands",JSONArray(listOf(runtimeBand(n.frequencyMhz)))))}
        }){Text("Start listening")}})
    }
}

@Composable internal fun SetupPanel(report:String?,busy:Boolean,onSetup:()->Unit,onProbe:()->Unit){
    val state=remember(report){runCatching {JSONObject(report?:"{}")} .getOrDefault(JSONObject())}
    val ready=state.optBoolean("runtime_ready")
    Surface(color=Surface,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(ready)"Runtime ready" else "Set up Bender",fontWeight=FontWeight.Bold)
            Text(if(ready)state.optString("message") else "Install the bundled ARM64 tools, grant root, then check the radio. No terminal commands or existing NetHunter installation are required. Built for the OnePlus 13 internal QCA interface; compatible builds may be tried. Recovery is not guaranteed on untested builds.",color=Muted,fontSize=12.sp)
            if(!ready&&state.optString("message").isNotBlank())Text(state.optString("message"),color=Color(0xFFE9D66B),fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(!ready)Button(enabled=!busy,onClick=onSetup){Text("Install runtime")}
                OutlinedButton(enabled=!busy,onClick=onProbe){Text("Check radio")}
            }
            if(!ready)Text("Existing NetHunter users can try Check radio first. Setup runs locally and leaves that installation intact.",color=Muted,fontSize=11.sp)
        }
    }
}

@Composable internal fun IdentityPanel(state:IdentityPreflight,enabled:Boolean,privacyPending:Boolean,onRestore:()->Unit,onCheck:()->Unit,onWifi:()->Unit,strict:Boolean=false,onStrict:(Boolean)->Unit={}) {
    var details by remember {mutableStateOf(false)}
    Surface(color=Surface,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Automatic privacy setup",fontWeight=FontWeight.Bold)
            Text("Start a scan, capture or test normally. Bender prepares the Wi-Fi filter and restores it afterward, including cancellation. Cellular and Bluetooth stay available by default. Offline analysis changes no connectivity settings.",color=Muted,fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=strict,onCheckedChange=onStrict,enabled=enabled&&!privacyPending);Text("Strict radio isolation",Modifier.padding(start=8.dp))}
            if(strict)Text("Strict mode temporarily pauses cellular and Bluetooth and blocks ordinary IP traffic on all interfaces. Saved settings are restored when the operation ends.",color=Muted,fontSize=12.sp)
            Text("Android's randomized MAC is checked before transmission. If it is unavailable, enable it in the lab network's Wi-Fi settings.",color=Muted,fontSize=12.sp)
            if(privacyPending)OutlinedButton(enabled=enabled,onClick=onRestore){Text("Restore normal connectivity")}
            TextButton(onClick=onWifi){Text("Wi-Fi settings")}
            TextButton(onClick={details=!details}){Text(if(details)"Hide details" else "What changes / details")}
            if(details) {
                Text("Privacy / identity preflight",fontWeight=FontWeight.Bold)
                Text("The default filter covers ordinary IPv4 / IPv6 output on wlan0. It does not hide association identifiers or block every raw DHCP or firmware packet. MAC randomization does not prove unlinkability. No anonymity, hostname suppression or public-IP masking is claimed. IMEI is neither read nor logged.",color=Muted,fontSize=12.sp)
                Text("Check again is a read-only strict-profile snapshot. Normal tests prepare their own profile; this snapshot is not a prerequisite.",color=Muted,fontSize=12.sp)
                OutlinedButton(enabled=enabled,onClick=onCheck){Text("Check again")}
                Text(state.checkedAt?.let {"Last checked: ${stamp(it)}"}?:"Last checked: never / unknown",fontSize=12.sp,color=Muted)
                state.checks.forEach {check->Text("${check.id}: ${check.status}\n${check.reason}",fontSize=12.sp,color=Muted)}
            }
        }
    }
}

internal fun labBlocker(n:NetworkEntity?,c:ClientEntity?,busy:Boolean,recovery:Boolean):String? {
    if(busy)return "Wait for the current radio operation to finish."
    if(recovery)return "Restore Wi-Fi before running another experiment."
    if(n==null||c==null)return "Select an AP and client first."
    if(c.apId!=n.id)return "Select a client from this AP's dossier."
    if(n.frequencyMhz==null||n.channel==null)return "Select an AP with an explicit frequency and channel."
    return null
}
internal fun workshopDestination(operation:String?):String=when(operation){
    "setup","probe","restore","identity","prepare","privacy_restore","privacy_status"->"run"
    "capture"->"radar"
    "lab"->"dossiers"
    "analyze"->"import"
    "wps_pixie"->"wps"
    else->"prerequisites"
}
@Composable internal fun CaptureControls(busy:Boolean,canFinish:Boolean,onFinish:()->Unit,onCancel:()->Unit) {
    if(busy)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
        if(canFinish)TextButton(onClick=onFinish){Text("Finish & save")}
        TextButton(onClick=onCancel){Text("Cancel")}
    }
}
@Composable internal fun CaptureExport(available:Boolean,onExport:()->Unit) {
    if(available)Column(Modifier.padding(horizontal=12.dp)){
        TextButton(onClick=onExport){Text("Export private PCAP")}
        Text("Raw packets include network identifiers and may include authentication traffic.",color=Muted,fontSize=11.sp)
    }
}
/** Observe controller release inside the lazy item, even if its Room row is unchanged. */
@Composable internal fun SessionCaptureExport(origin:String,onExport:()->Unit,
    radioState:kotlinx.coroutines.flow.StateFlow<dev.sir.benderwifi.radio.RadioUiState> = RadioController.state,
    artifact:(String)->java.io.File? = RadioController::captureArtifact) {
    val radio by radioState.collectAsState()
    val running=radio.busy&&radio.sessionId==origin.removePrefix("imported:")
    CaptureExport(!running&&artifact(origin)!=null,onExport)
}
private fun loadCatalog(context:Context):List<CatalogAction> =runCatching {
    val root=JSONObject(context.assets.open("runtime/catalog.json").bufferedReader().use {it.readText()});val actions=root.getJSONArray("actions")
    (0 until actions.length()).map {i->val a=actions.getJSONObject(i);val types=a.optJSONArray("target_types")?:JSONArray();CatalogAction(a.getString("id"),a.getString("title"),(0 until types.length()).map {types.getString(it)},a.optString("status","unavailable"),a.optString("description"),a.optString("blocker"),if(a.isNull("operation"))null else a.optString("operation").ifBlank {null})}
}.getOrElse {listOf(CatalogAction("catalog_unavailable","Capability catalog unavailable",listOf("global"),"unavailable","The runtime catalog could not be loaded.","Rebuild with the packaged catalog. No experimental operation is enabled by a missing catalog.",null))}
internal fun bandFor(frequency:Int?)=when(frequency?:0){in 2400..2500->"2.4 GHz";in 4900..5924->"5 GHz";in 5925..7125->"6 GHz";else->"Unknown"}
private fun runtimeBand(frequency:Int?)=when(bandFor(frequency)){"2.4 GHz"->"2.4";"5 GHz"->"5";"6 GHz"->"6";else->"unknown"}
internal fun stamp(seconds:Double)=if(seconds>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date((seconds*1000).toLong())) else "Unknown time"

@Composable internal fun RecoveryStatusBanner(enabled:Boolean,onCheck:()->Unit) {
    Surface(color=Color(0xFF42391E),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recovery state unchecked",fontWeight=FontWeight.Bold,fontSize=13.sp)
                Text("Check saved recovery before testing.",fontSize=11.sp)
            }
            TextButton(enabled=enabled,onClick=onCheck){Text("Check recovery state",fontSize=11.sp)}
        }
    }
}
@Composable private fun SessionCaptureDelete(origin:String,busy:Boolean,onDelete:()->Unit) {
    if(dev.sir.benderwifi.radio.RadioAssessment.eligibleOrigin(origin.removePrefix("imported:")))
        TextButton(enabled=!busy,onClick=onDelete){Text("Delete raw capture")}
}
internal fun integritySummary(source:String):String {
    val counts=runCatching {JSONObject(source).optJSONObject("frame_integrity")}.getOrNull()
        ?:return "Frame integrity counts unavailable for this older/imported session."
    return "Integrity: ${counts.optInt("failed")} frames excluded · ${counts.optInt("verified")} checksum verified · ${counts.optInt("not_flagged")+counts.optInt("unknown")} unverified. Raw PCAP preserved until deleted."
}
@Composable internal fun SecurityDetails(network:NetworkEntity) {
    val json=remember(network.rawJson){runCatching {JSONObject(network.rawJson)}.getOrDefault(JSONObject())}
    val variants=json.optJSONArray("security_variants")?:JSONArray()
    fun values(v:JSONObject,key:String):String = v.optJSONArray(key)?.let {a->(0 until a.length()).map {a.optString(it)}.filter {it.isNotBlank()}.joinToString(", ")}.orEmpty().ifBlank {"unknown"}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Advertised security · ${json.optString("security_quality","unknown")}",fontWeight=FontWeight.Bold)
        if(variants.length()==0)Text("Detailed classification unavailable in this older record. Analyze its capture again.",fontSize=12.sp,color=Muted)
        for(i in 0 until variants.length())variants.optJSONObject(i)?.let {v->
            InfoCard(v.optString("label","unknown"),listOf("Protocol" to values(v,"protocols"),"Authentication" to values(v,"authentication"),"Pairwise / group ciphers" to "${values(v,"pairwise_ciphers")} / ${values(v,"group_ciphers")}","Advertised PMF" to values(v,"pmf"),"Evidence quality" to v.optString("quality","unknown")))
        }
        Text("Different advertisements stay separate. These fields do not establish negotiated protection.",fontSize=11.sp,color=Muted)
    }
}
@Composable private fun EmptyCard(title:String,body:String){Surface(color=Surface,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,fontWeight=FontWeight.Bold);Text(body,color=Muted,fontSize=13.sp)}}}
@Composable private fun InfoCard(title:String,values:List<Pair<String,String>>,onClick:(()->Unit)?=null){Surface(color=Surface,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,fontWeight=FontWeight.Bold);values.forEach {(label,value)->Text("$label\n$value",color=Muted,fontSize=12.sp)}}}}
@Composable internal fun ActionCard(a:CatalogAction,enabled:Boolean=true,onClick:()->Unit){Surface(color=Surface,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Row {Text(a.title,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(if(a.operation==null)"Not implemented" else a.status.replace('_',' '),color=if(a.status=="validated")Lime else Color(0xFFE9D66B),fontSize=10.sp)};Text(a.description,fontSize=12.sp,color=Muted);if(a.blocker.isNotBlank())Text(a.blocker,fontSize=12.sp,color=Color(0xFFE9D66B));OutlinedButton(enabled=enabled,onClick=onClick){Text(if(a.operation==null||a.status=="unavailable"||a.status=="awaiting_evidence")"Why unavailable" else "Open action")}}}}
@Composable private fun CredentialDialog(onDismiss:()->Unit,onSave:(String,String,String)->Unit){
    var label by remember {mutableStateOf("")};var username by remember {mutableStateOf("")};var secret by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest={secret="";onDismiss()},title={Text("Add encrypted credential")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(label,{label=it},label={Text("Label / network name")},singleLine=true)
        OutlinedTextField(username,{username=it},label={Text("Username (optional)")},singleLine=true)
        OutlinedTextField(secret,{secret=it},label={Text("Password / secret")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
        Text("Saved as manual, unverified. Authenticate with your device screen lock to encrypt.",fontSize=12.sp,color=Muted)
    }},dismissButton={TextButton(onClick={secret="";onDismiss()}){Text("Cancel")}},confirmButton={Button(enabled=label.isNotBlank()&&secret.isNotEmpty(),onClick={val value=secret;secret="";onSave(label,username,value)}){Text("Save encrypted")}})
}
@Composable private fun BenderMark(modifier:Modifier=Modifier){Canvas(modifier){
    val pixel=size.width/16f
    fun block(x:Int,y:Int,w:Int,h:Int,color:Color){drawRect(color,Offset(x*pixel,y*pixel),Size(w*pixel,h*pixel))}
    val silver=Color(0xFFB8C2BC);val shade=Color(0xFF76867C);val yellow=Color(0xFFFFE454)
    block(7,0,2,3,silver);block(5,3,6,1,silver);block(3,4,10,10,silver);block(2,6,1,6,shade);block(13,6,1,6,shade);block(4,7,8,4,Charcoal);block(5,8,2,2,yellow);block(9,8,2,2,yellow);block(5,12,6,1,Charcoal);block(4,14,8,2,shade)
}}

@Composable internal fun CredentialRevealDialog(secret:String,onClose:()->Unit,onCopy:()->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text("Credential")},
        text={SelectionContainer {Text(secret,fontFamily=FontFamily.Monospace)}},
        dismissButton={TextButton(onClick=onCopy){Text("Copy")}},
        confirmButton={TextButton(onClick=onClose){Text("Close")}})
}
