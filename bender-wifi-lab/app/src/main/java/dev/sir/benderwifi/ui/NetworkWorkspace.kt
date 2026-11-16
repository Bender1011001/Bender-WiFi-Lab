package dev.sir.benderwifi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sir.benderwifi.data.LabRepository
import dev.sir.benderwifi.data.NetworkWork
import dev.sir.benderwifi.radio.RadioController
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NetworkWorkspace(networkId:String,repo:LabRepository,onEvidence:(String)->Unit,onExportCapture:(String)->Unit,onMessage:(String)->Unit) {
    val work by remember(networkId){repo.networkExperiments(networkId)}.collectAsState(initial=emptyList())
    val sessions by remember(networkId){repo.networkSessions(networkId)}.collectAsState(initial=emptyList())
    val exchanges by remember(networkId){repo.networkHandshakes(networkId)}.collectAsState(initial=emptyList())
    val savedNote by remember(networkId){repo.networkNote(networkId)}.collectAsState(initial=null)
    val scope=rememberCoroutineScope()
    var notes by rememberSaveable(networkId){mutableStateOf("")}
    var editing by rememberSaveable(networkId){mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var visible by rememberSaveable(networkId){mutableIntStateOf(30)}
    LaunchedEffect(savedNote?.updatedAt,networkId){if(!editing)notes=savedNote?.text.orEmpty()}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Saved work for this network",fontWeight=FontWeight.Bold,fontSize=18.sp)
        Text("${sessions.size} capture records · ${exchanges.size} handshake exchanges · ${work.size} actions",fontSize=12.sp)
        Text("Handshake exchanges",fontWeight=FontWeight.Bold)
        if(exchanges.isEmpty())Text("No handshake exchange recorded. Start handshake capture, then reconnect an owned client after listening begins.",fontSize=12.sp)
        exchanges.take(visible).forEach {exchange->
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                Text(NetworkWork.handshakeLabel(exchange.rawJson),fontWeight=FontWeight.Bold)
                Text("Observed message coverage; password verification is separate.",fontSize=12.sp)
                TextButton(onClick={onEvidence(exchange.rawJson)}){Text("Inspect exchange")}
                SessionCaptureExport(exchange.sessionId,{onExportCapture(exchange.sessionId)})
            }}
        }
        Text("Sessions and actions",fontWeight=FontWeight.Bold)
        if(work.isEmpty()&&sessions.isEmpty())Text("Actions and captures will be saved here automatically.",fontSize=12.sp)
        work.take(visible).forEach {run->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text("${when(run.operation){"lab"->"Directed deauth";"capture"->"Capture";else->run.operation}} · ${run.state}",fontWeight=FontWeight.Bold)
                Text(DateFormat.getDateTimeInstance().format(Date(run.createdAt)),fontSize=12.sp)
                TextButton(onClick={onEvidence(run.result)}){Text("Result")}
                SessionCaptureExport(run.id,{onExportCapture(run.id)})
            }}
        }
        sessions.filter {saved->work.none {it.id in NetworkWork.origins(saved.id)}}.take(visible).forEach {saved->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text(saved.origin,fontWeight=FontWeight.Bold)
                Text(DateFormat.getDateTimeInstance().format(Date(saved.importedAt)),fontSize=12.sp)
                TextButton(onClick={scope.launch {runCatching {repo.getSessionEvidence(saved.id)}.onSuccess(onEvidence).onFailure {onMessage("Could not open saved evidence")}}}){Text("Open observations")}
                SessionCaptureExport(saved.id,{onExportCapture(saved.id)})
            }}
        }
        if(maxOf(work.size,sessions.size,exchanges.size)>visible)TextButton(onClick={visible+=30}){Text("Show older saved work")}
        OutlinedTextField(notes,{if(it.length<=10000){notes=it;editing=true}},label={Text("Network notes")},supportingText={Text("Saved locally. Keep passwords in the Vault.")},modifier=Modifier.fillMaxWidth(),minLines=3)
        Button(enabled=editing&&!saving,onClick={
            val text=notes;saving=true
            scope.launch {runCatching {repo.saveNote(networkId,text)}.onSuccess {if(notes==text)editing=false;onMessage("Network notes saved")}.onFailure {onMessage("Notes could not be saved; your edits are still visible")};saving=false}
        }){Text(if(saving)"Saving…" else "Save notes")}
    }
}
