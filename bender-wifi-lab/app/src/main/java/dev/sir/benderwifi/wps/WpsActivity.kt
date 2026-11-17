package dev.sir.benderwifi.wps

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.sir.benderwifi.data.LabRepository
import dev.sir.benderwifi.data.NetworkWork
import dev.sir.benderwifi.radio.RadioController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Foreground-only offline analysis. Exchange material and raw output are never saved. */
class WpsActivity:ComponentActivity() {
    private var exchange:PixieExchange?=null
    private var task:Job?=null
    private var busy by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var message by mutableStateOf("Import one WPS exchange to begin.")
    private var networkLabel by mutableStateOf("Unassigned network")
    private var targetFrequency by mutableIntStateOf(0)
    private var liveSession by mutableStateOf<String?>(null)
    private val offlineProgress=MutableStateFlow<WpsProgress?>(null)
    private val networkId by lazy {NetworkWork.networkId(intent.getStringExtra("network_id"))}
    private val repo by lazy {LabRepository.get(this)}
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            task=lifecycleScope.launch {
                busy=true;ready=false;exchange=null;message="Reading WPS exchange…"
                try {
                    exchange=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)?.use {PixieProtocol.parse(PixieProtocol.read(it))}?:error("Cannot open file")}
                    ready=true;message="Exchange ready. Results will be saved under the selected network."
                }catch(e:CancellationException){message="Import cancelled.";throw e}
                catch(e:IllegalArgumentException){message=e.message?:"Invalid WPS exchange."}
                catch(_:Exception){message="Could not read that exchange. Select a local text or JSON file."}
                finally {busy=false}
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        liveSession=savedInstanceState?.getString("live_wps_session")
        lifecycleScope.launch {networkId?.let {id->repo.getNetwork(id)?.let {networkLabel="${it.ssidDisplay.ifBlank {"Hidden SSID"}} · ${it.bssid}";targetFrequency=it.frequencyMhz?:0}}}
        setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFC6F35B),background=Color(0xFF111512),surface=Color(0xFF1D241F))){
            val radio by RadioController.state.collectAsStateWithLifecycle()
            val latest by WpsProgressStore.state.collectAsStateWithLifecycle()
            val offline by offlineProgress.collectAsStateWithLifecycle()
            val progress=latest?.takeIf {it.session==liveSession}?:offline
            val live=radio.busy && radio.operation=="wps" && radio.sessionId==liveSession
            val blocker=when {
                radio.recoveryRequired -> "Restore Wi-Fi in Workshop before starting another radio test."
                radio.privacyPending -> "Restore the pending connectivity session in Workshop first."
                radio.busy && !live -> "Another radio operation is running."
                networkId==null -> "Select a network from Radar to collect a live exchange."
                targetFrequency !in 2412..5895 -> "Live WPS requires a selected 2.4 or 5 GHz AP."
                else -> null
            }
            WpsScreen(networkLabel,busy||live,ready,progress?.detail?:message,
                onBack={finish()},onImport={liveSession=null;offlineProgress.value=null;picker.launch(arrayOf("text/*","application/json","application/octet-stream"))},
                onRun={runSolver()},onCancel={if(live)RadioController.cancel() else task?.cancel()},
                liveAvailable=blocker==null && !radio.busy,
                onLive={startLive("pixie")},onCommon={startLive("common_pins")},progress=progress,blocker=if(live)null else blocker)
        }}
    }
    private fun startLive(strategy:String){
        if(busy||networkId==null||targetFrequency !in 2412..5895)return
        val bssid=networkId!!.removePrefix("ap:")
        offlineProgress.value=null
        val before=RadioController.state.value.sessionId
        RadioController.start("wps",JSONObject().put("bssid",bssid).put("frequency",targetFrequency).put("strategy",strategy))
        val state=RadioController.state.value
        liveSession=state.sessionId.takeIf {state.busy && state.operation=="wps" && it!=before}
        if(liveSession==null)message=state.phase
    }
    private suspend fun record(id:String,state:String,verified:Boolean=false,saved:Boolean=false){
        val request=JSONObject().put("bssid",networkId?.removePrefix("ap:"))
        val result=JSONObject().put("schema_version",1).put("state",state).put("offline",true)
            .put("transcript_verified",verified).put("credential_saved",saved).put("enrollment_performed",false)
            .put("target_attribution","user_selected").put("solver","pixiewps").put("deadline_seconds",120)
        repo.recordExperiment(id,"wps_pixie",state,JSONObject().put("request",request).put("result",result).toString(),request)
    }
    private fun runSolver(){
        val input=exchange?:return
        if(busy)return
        task=lifecycleScope.launch {
            busy=true;ready=false
            val id="wps_${UUID.randomUUID()}"
            liveSession=null
            offlineProgress.value=WpsProgress(id,SystemClock.elapsedRealtime()).event("solving","Starting local Pixie Dust search",SystemClock.elapsedRealtime(),120_000)
            var committed=false
            try {
                WpsRecovery.await()
                record(id,"running")
                message="Running Pixie Dust locally · up to 120 seconds · keep this screen open."
                val pin=PixieRunner.solve(File(applicationInfo.nativeLibraryDir,"libbender_pixie.so"),input,
                    onHeartbeat={offlineProgress.value=offlineProgress.value?.heartbeat(SystemClock.elapsedRealtime(),"Local solver process")})
                if(pin==null){record(id,"not_found");message="No PIN found in this bounded search. This does not prove the AP is secure."}
                else if(pin.isEmpty()){record(id,"empty_pin_verified",verified=true);message="An empty PIN matched both WPS hashes. Recorded in Saved work; no network password was obtained."}
                else {
                    // Complete the short encrypted handoff even if the activity backgrounds now.
                    withContext(NonCancellable){
                        repo.saveWpsPin(pin,networkId,"WPS PIN · $networkLabel",id,JSONObject().put("bssid",networkId?.removePrefix("ap:")),
                            JSONObject().put("state","pin_verified").put("offline",true).put("transcript_verified",true)
                                .put("credential_saved",true).put("enrollment_performed",false).put("target_attribution","user_selected"))
                        committed=true
                    }
                    message="PIN verified against both WPS hashes and encrypted in Vault. No network password was obtained; enrollment is a separate step."
                }
            }catch(_:TimeoutCancellationException){withContext(NonCancellable){record(id,"timed_out")};message="Search timed out. No PIN was saved; this is not a security verdict."}
            catch(e:CancellationException){
                // A cancellation delivered after the atomic save must not relabel
                // the persisted PIN and successful result as a cancelled search.
                if(!committed){withContext(NonCancellable){record(id,"cancelled")};message="Search cancelled. It will not restart automatically."}
                throw e
            }
            catch(_:Exception){withContext(NonCancellable){runCatching {record(id,"failed")}};message="The search or encrypted save did not complete. Inspect Saved work; no unverified PIN is accepted."}
            finally {offlineProgress.value=offlineProgress.value?.finish(message,SystemClock.elapsedRealtime());exchange=null;ready=false;busy=false}
        }
    }
    override fun onStop(){
        task?.cancel();exchange=null;ready=false
        if(liveSession!=null && RadioController.state.value.operation=="wps" && RadioController.state.value.sessionId==liveSession)RadioController.cancel()
        super.onStop()
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("live_wps_session",liveSession);super.onSaveInstanceState(outState)}
}

@Composable internal fun WpsScreen(network:String,busy:Boolean,ready:Boolean,message:String,onBack:()->Unit,onImport:()->Unit,onRun:()->Unit,onCancel:()->Unit,liveAvailable:Boolean=false,onLive:()->Unit={},onCommon:()->Unit={},progress:WpsProgress?=null,blocker:String?=null){
    Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
        TextButton(onClick=onBack){Text("‹ Back to Wi-Fi")}
        Text("WPS · Pixie Dust",fontSize=24.sp)
        Text(network,fontSize=13.sp)
        if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton(onClick=onCancel){Text("Cancel")}}
        if(progress!=null)WpsProgressPanel(progress) else Text(message)
        if(!busy){blocker?.let {Text(it)};Button(enabled=liveAvailable,onClick=onLive){Text("Collect and run Pixie Dust")};OutlinedButton(enabled=liveAvailable,onClick=onCommon){Text("Check 3 common PINs")};Text("Offline exchange import",color=MaterialTheme.colorScheme.primary);Button(onClick=onImport){Text("Import WPS exchange")};Button(enabled=ready,onClick=onRun){Text("Run Pixie Dust")}}
        Text("Live WPS on the internal radio",color=MaterialTheme.colorScheme.primary)
        Text("Collect → search locally → request AP settings if a PIN is verified → save encrypted results → restore Wi-Fi. Root and the bundled radio runtime are required.")
        Text("Factory PIN clues: check the router label or its local admin page. WPS advertisements can show manufacturer, model and setup lock state, but they do not reveal the PIN. This check tries 00000000, 12345670 and 11111115 only. It stops if an attempt is inconclusive or the AP reports setup locked.",fontSize=13.sp)
        Text("Live collection is experimental until tested on this phone and AP. A WPS advertisement is only a capability hint. Failed collection or a missing PIN is not a security verdict.",fontSize=13.sp)
        Text("JSON fields: pke, pkr, e_hash1, e_hash2, authkey, e_nonce. Use hex strings. Import one complete exchange; combined logs are rejected. The original file stays where you selected it. Bender keeps exchange material in memory and saves only the result and encrypted PIN.",fontSize=12.sp)
        Text("Changing apps cancels the active test. Results belong to the selected BSSID. A recovered PIN and an AP-provided password are separate results; connection with that password has not been tested.",fontSize=12.sp)
    }}
}

internal fun wpsDuration(seconds:Long):String="${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"

@Composable internal fun WpsProgressPanel(progress:WpsProgress,nowOverride:Long?=null){
    var tick by remember(progress.session){mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(progress.session,progress.finishedMs,nowOverride){
        if(nowOverride==null)while(progress.finishedMs==null){tick=SystemClock.elapsedRealtime();delay(1000)}
    }
    val now=nowOverride?:tick
    var expanded by remember(progress.session){mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(WpsMessages.title(progress.stage),color=MaterialTheme.colorScheme.primary,fontSize=18.sp)
        Text(progress.detail)
        Text("Elapsed ${wpsDuration(progress.elapsed(now))}" + if(progress.finishedMs==null)" · stage ${wpsDuration((now-progress.stageStartedMs).coerceAtLeast(0)/1000)}" else "",fontSize=12.sp)
        if(progress.finishedMs==null){
            progress.deadlineMs?.let {deadline->Text(if(deadline>now)"Stage limit in ${wpsDuration((deadline-now+999)/1000)}" else "Stage limit reached · waiting for stop and cleanup",fontSize=12.sp)}
            Text("Last stage / protocol event ${((now-progress.lastEventMs).coerceAtLeast(0)/1000)}s ago",fontSize=12.sp)
            if(progress.stalled(now))Text("No worker update for ${progress.signalAge(now)}s. It may be stalled; Cancel requests cleanup.",color=MaterialTheme.colorScheme.error)
            else progress.heartbeatMs?.let {
                val age=(now-it).coerceAtLeast(0)/1000
                Text(if(age<10)"${progress.worker}: alive · checked ${age}s ago" else "${progress.worker}: last heartbeat ${age}s ago",fontSize=12.sp)
            }
            Text("A heartbeat confirms worker liveness, not an AP response or a recovered PIN.",fontSize=11.sp)
        }
        TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide event timeline" else "Show event timeline (${progress.events.size})")}
        (if(expanded)progress.events else progress.events.takeLast(3)).forEach {event->
            Text("${wpsDuration((event.atMs-progress.startedMs).coerceAtLeast(0)/1000)} · ${event.text}",fontSize=12.sp)
        }
    }}
}
