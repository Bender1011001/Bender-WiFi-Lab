package dev.sir.benderwifi.wps

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class WpsEvent(val atMs:Long,val stage:String,val text:String)

/** Monotonic times, session identity and activity are separate from worker heartbeats. */
data class WpsProgress(
    val session:String,val startedMs:Long,val stage:String="setup",val stageStartedMs:Long=startedMs,
    val detail:String="Starting the root supervisor",val lastEventMs:Long=startedMs,
    val heartbeatMs:Long?=null,val worker:String?=null,val deadlineMs:Long?=null,
    val finishedMs:Long?=null,val events:List<WpsEvent> = emptyList()
) {
    fun event(stage:String,text:String,now:Long,limitMs:Long?=null):WpsProgress {
        if(finishedMs!=null)return this
        val changed=stage!=this.stage
        return copy(stage=stage,stageStartedMs=if(changed)now else stageStartedMs,detail=text,lastEventMs=now,
            heartbeatMs=if(changed)null else heartbeatMs,worker=if(changed)null else worker,
            deadlineMs=limitMs?.let {now+it}?:if(changed)null else deadlineMs,
            events=(events+WpsEvent(now,stage,text)).takeLast(80))
    }
    fun heartbeat(now:Long,worker:String):WpsProgress = if(finishedMs!=null)this else copy(heartbeatMs=now,worker=worker)
    fun finish(text:String,now:Long):WpsProgress = if(finishedMs!=null)this else event("finished",text,now).copy(finishedMs=now,deadlineMs=null)
    fun elapsed(now:Long):Long=((finishedMs?:now)-startedMs).coerceAtLeast(0)/1000
    fun signalAge(now:Long):Long=(now-maxOf(heartbeatMs?:lastEventMs,lastEventMs)).coerceAtLeast(0)/1000
    fun stalled(now:Long):Boolean=finishedMs==null && signalAge(now)>=10
    fun json():JSONObject=JSONObject().put("schema_version",1).put("session",session)
        .put("elapsed_seconds",elapsed(finishedMs?:startedMs)).put("finished",finishedMs!=null)
        .put("events",JSONArray().apply {events.forEach {put(JSONObject().put("elapsed_ms",it.atMs-startedMs).put("stage",it.stage).put("message",it.text))}})
}

object WpsMessages {
    val stages=mapOf("setup" to "Set up radio", "preparing_radio" to "Prepare private interface",
        "collecting_exchange" to "Collect WPS exchange", "solving" to "Run Pixie Dust locally",
        "requesting_credentials" to "Request AP settings", "saving" to "Save verified result",
        "restoring" to "Restore Wi-Fi", "finished" to "Finished")
    fun title(stage:String):String=stages[stage]?:Regex("trying_pin_([1-3])_of_3").matchEntire(stage)?.let {"Check PIN candidate ${it.groupValues[1]} of 3"}?:"WPS"
    val details=mapOf(
        "starting_supplicant" to "Starting the managed WPS worker",
        "control_ready" to "WPS worker answered its control check",
        "scanning_ap" to "Looking for the selected AP on its channel",
        "ap_found" to "Fresh AP record found; WPS is advertised",
        "wps_requested" to "WPS request accepted locally; waiting for the AP",
        "associated" to "Association reported by the WPS worker",
        "disconnected" to "WPS worker reported a disconnection",
        "eap_started" to "EAP exchange started",
        "eap_method" to "EAP method selected",
        "eap_failed" to "EAP failure reported",
        "wps_failed" to "WPS failure reported by the worker",
        "wps_deferred" to "AP deferred the WPS exchange (M2D)",
        "wps_timeout" to "WPS worker reported a timeout",
        "wps_success" to "WPS success event received; validating the result",
        "monitor_unavailable" to "Detailed connection events unavailable; worker status remains visible",
        "exchange_collected" to "Authenticated WPS M3 received; exchange collected",
        "pin_rejected" to "AP exchange confirmed that this PIN candidate was rejected",
        "candidate_pause" to "Waiting 5 seconds before the next configured candidate",
        "credential_received" to "Authenticated AP settings received",
        "pin_verified" to "PIN verified against both hashes and encrypted in Vault",
        "credential_saved" to "AP-provided password encrypted in Vault",
        "not_found" to "No usable PIN found in this bounded search",
        "bridge_closed" to "Collector connection ended; waiting for the supervisor result",
        "solver_timeout" to "Local Pixie Dust search reached its 120-second limit")
    val failures=mapOf(
        "selected_ap_wps_unavailable" to "Selected AP was not found with WPS on this channel",
        "ap_setup_locked" to "AP reports that WPS setup is locked",
        "supplicant_unavailable" to "Managed WPS worker did not become ready",
        "autoconnect_control_unavailable" to "WPS worker could not disable automatic connections",
        "scan_rejected" to "Driver rejected the AP scan request",
        "wps_request_rejected" to "WPS worker rejected the enrollment request",
        "wps_exchange_incomplete" to "WPS worker exited without a complete authenticated result",
        "wps_exchange_timed_out" to "No complete WPS exchange before the 45-second limit",
        "secondary_interface_in_use" to "The secondary Wi-Fi interface is already in use",
        "wps_recovery_pending" to "An earlier WPS interface needs restoration",
        "privacy_session_missing" to "Automatic privacy setup was not established",
        "privacy_filter_missing" to "Automatic privacy filter could not be verified",
        "random_mac_not_applied" to "The private interface did not accept its temporary MAC",
        "primary_interface_changed" to "The primary Wi-Fi interface changed during setup",
        "vault_save_unconfirmed" to "Encrypted Vault save was not confirmed",
        "worker_error" to "WPS worker stopped unexpectedly; inspect this session in Evidence")
}

/** Every update must match the active session; late callbacks cannot overwrite a new run. */
object WpsProgressStore {
    private val mutable=MutableStateFlow<WpsProgress?>(null)
    val state:StateFlow<WpsProgress?> = mutable
    @Synchronized fun begin(session:String){val now=SystemClock.elapsedRealtime();mutable.value=WpsProgress(session,now).event("setup","Starting the root supervisor",now)}
    @Synchronized fun event(session:String,stage:String,text:String,limitMs:Long?=null){
        mutable.value?.takeIf {it.session==session}?.let {mutable.value=it.event(stage,text,SystemClock.elapsedRealtime(),limitMs)}
    }
    @Synchronized fun detail(session:String,code:String,limitMs:Long?=null){
        val current=mutable.value?.takeIf {it.session==session}?:return
        WpsMessages.details[code]?.let {mutable.value=current.event(current.stage,it,SystemClock.elapsedRealtime(),limitMs)}
    }
    @Synchronized fun heartbeat(session:String,worker:String){
        mutable.value?.takeIf {it.session==session}?.let {mutable.value=it.heartbeat(SystemClock.elapsedRealtime(),worker)}
    }
    @Synchronized fun supervisor(session:String,text:String){
        val current=mutable.value?.takeIf {it.session==session}?:return
        val restoring=text.startsWith("Removing the private WPS") || text.startsWith("Restoring")
        if(restoring || current.stage in setOf("setup","restoring"))
            mutable.value=current.event(if(restoring)"restoring" else current.stage,text,SystemClock.elapsedRealtime())
    }
    @Synchronized fun finish(session:String,text:String):WpsProgress? {
        val current=mutable.value?.takeIf {it.session==session}?:return null
        return current.finish(text,SystemClock.elapsedRealtime()).also {mutable.value=it}
    }
}
