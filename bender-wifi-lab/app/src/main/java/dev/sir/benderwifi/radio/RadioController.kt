package dev.sir.benderwifi.radio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import dev.sir.benderwifi.data.LabRepository
import dev.sir.benderwifi.wps.LiveWpsBridge
import dev.sir.benderwifi.wps.WpsProgressStore
import dev.sir.benderwifi.wps.WpsMessages
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RadioUiState(val busy: Boolean = false, val phase: String = "Ready · run a radio check", val sessionId: String? = null, val recoveryRequired: Boolean = false, val identityReport: String? = null, val privacyReport: String? = null, val privacyPending: Boolean = false, val operation:String?=null,val captureReady:Boolean=false,val recoveryChecked:Boolean=false)

/** Serialized local transport. No action is ever replayed after process death. */
object RadioController {
    private lateinit var context: Context
    private val mutable = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = mutable
    private val runtimeMutable=MutableStateFlow<String?>(null)
    val runtimeState:StateFlow<String?> = runtimeMutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var current: File? = null
    private var queuedOperation = ""
    private var bluetoothOwner: String? = null
    @Synchronized fun reserveBluetooth(id: String): String? {
        if (mutable.value.busy || bluetoothOwner != null) return "Another radio operation is running; wait for it to finish"
        if (mutable.value.privacyPending) return "Restore normal connectivity in Wi-Fi Lab before using Bluetooth"
        if (mutable.value.recoveryRequired) return "Resolve the saved Wi-Fi recovery state before using Bluetooth"
        bluetoothOwner = id
        return null
    }
    @Synchronized fun releaseBluetooth(id: String) { if (bluetoothOwner == id) bluetoothOwner = null }
    private var importCancellation:java.util.concurrent.atomic.AtomicBoolean?=null
    @Volatile private var importInput:java.io.InputStream?=null
    private val outputs = listOf("capture.pcap", "capture.csv", "capture.log", "result.json", "attempt.json", "device.json", "compatibility.json", "environment.txt", "radio-state.json", "scan-plan.json", "scan-summary.json", "decoder.log", "observations.json", "observations-v1.json", "analysis-state.json", "analysis.log", "identity-state.json", "privacy-state.json", "privacy-preparation.json", "runtime-state.json", "install.log", "retention-state.json")
    private val sessions get() = File(context.filesDir, "qca-sessions")
    private val tools get() = File(context.filesDir, "radio-tools")
    @Synchronized fun init(owner: Context) {
        if (::context.isInitialized) return
        context = owner.applicationContext
        sessions.mkdirs()
        tools.mkdirs()
        reconcile()
    }
    fun sessionDirectory(id: String): File? = if (RadioAssessment.eligibleOrigin(id)) File(sessions,id).takeIf { it.isDirectory } else null
    fun captureArtifact(origin:String):File? {
        val directory=sessionDirectory(origin.removePrefix("imported:"))?:return null
        if(current==directory)return null
        return listOf("capture.pcap","input.pcap").map {File(directory,it)}.firstOrNull {it.isFile&&it.length()>24&&it.canonicalFile.parentFile==directory.canonicalFile}
    }
    private fun read(file: File): JSONObject = readSessionJson(file)
    fun captureRequest(origin:String):JSONObject?=sessionDirectory(origin)?.let {directory->
        val journal=read(File(directory,"journal.json"))
        if(journal.optString("operation")=="capture"&&journal.optString("state")=="completed")read(File(directory,"request.json")) else null
    }
    private fun write(file: File, value: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun reconcile() {
        if (!::context.isInitialized || mutable.value.busy || bluetoothOwner != null) return
        mutable.value = mutable.value.copy(busy=true,phase="Checking saved sessions")
        scope.launch {
            var unresolved = false
            var failure = false
            var importFailure = false
            var identityReport: String? = mutable.value.identityReport
            var privacyReport: String? = mutable.value.privacyReport
            var privacyPending = false
            try {
                // Later verified restoration can resolve an earlier obligation. Analysis
                // and a mere managed-mode observation cannot erase it.
                sessions.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.substringAfter("bw_").substringBefore('_').toLongOrNull() ?: 0L }?.forEach { job ->
                    val journal = read(File(job, "journal.json"))
                    val operation = journal.optString("operation")
                    val radio = read(File(job,"radio-state.json"))
                    val runtime=read(File(job,"runtime-state.json"))
                    val identity = read(File(job,"identity-state.json"))
                    if(identity.has("allowed")) identityReport=identity.toString()
                    val processEnded = File(job,"exit.json").isFile
                    val started = journal.opt("supervisor_started") == true || journal.optString("state") == "recovery_required"
                    unresolved = RadioAssessment.recoveryAfter(unresolved,operation,started,radio)
                    val privacy=read(File(job,"privacy-state.json"))
                    if(privacy.has("pending")) privacyReport=privacy.toString()
                    privacyPending=RadioAssessment.privacyAfter(privacyPending,operation,started,privacy,radio)
                    val importError=importSavedObservations(job,operation,LabRepository.get(context)::importObservations)
                    if(importError!=null) importFailure=true
                    journal.put("import_error",importError)
                    val previous=journal.optString("state")
                    val intent=File(job,"attempt.json").length()>0
                    val result=read(File(job,"result.json"))
                    // Recover both sides of a crash between the journal and Room commits.
                    // Upserting evidence never launches or retries the operation.
                    val outcome=when {
                        processEnded || (operation=="lab" && intent) -> RadioAssessment.completion(read(File(job,"exit.json")).optInt("code",-1),result,unresolved,operation,intent,started)
                        previous in setOf("completed","failed","failed_before_launch","cancelled","uncertain") -> previous
                        else -> "interrupted"
                    }
                    runtimeMutable.value=RadioAssessment.runtimeAfter(runtimeMutable.value,operation,outcome,runtime)
                    reconcileSavedExperiment(job,operation,outcome,importError,LabRepository.get(context))
                    journal.put("state",outcome).put("replayed",false).put("recovery_required_after",unresolved)
                    write(File(job,"journal.json"),journal.toString())
                }
            } catch (_: Exception) {
                failure=true
                unresolved=true
            } finally {
                mutable.value = RadioUiState(false,when {
                    failure -> "Saved evidence unavailable · verify / restore Wi-Fi"
                    unresolved -> "Unresolved radio session · verify / restore Wi-Fi"
                    importFailure -> "Saved observations need attention · inspect session evidence"
                    else -> "Ready · run a radio check"
                },null,unresolved,identityReport,privacyReport,privacyPending,recoveryChecked=mutable.value.recoveryChecked)
            }
        }
    }
    @Synchronized fun start(operation: String, request: JSONObject = JSONObject()) {
        if (!::context.isInitialized || mutable.value.busy) return
        if (bluetoothOwner != null) { mutable.value = mutable.value.copy(phase="Stop the Bluetooth operation before using Wi-Fi tools"); return }
        try {
            require(operation in setOf("setup","probe","capture","lab","wps","restore","analyze","identity","prepare","privacy_restore","privacy_status","recovery_status","delete_capture","cleanup_captures")) { "Unsupported radio operation" }
            require(!mutable.value.recoveryRequired || operation in setOf("probe","restore","analyze","identity","privacy_restore","privacy_status","recovery_status","delete_capture","cleanup_captures")) { "Verify or restore the previous radio session first" }
            require(operation!="prepare" || !mutable.value.privacyPending) { "A saved privacy profile already exists. Check or restore it first." }
            require(operation !in setOf("capture","lab","wps") || !mutable.value.privacyPending) { "Restore the pending privacy session before starting another test." }
            val safe = JSONObject(request.toString())
            // Every lab start is an explicit UI action. No capture-age or ownership
            // policy is imposed here; the backend validates addresses and current
            // interface/channel permissions. Interrupted transmissions are never replayed.
            if (!safe.has("channel")) safe.put("channel",0)
            val id = "bw_${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val job = File(sessions,id)
            check(job.mkdir()) { "Cannot create session" }
            outputs.forEach { File(job,it).createNewFile() }
            if (operation == "analyze") {
                val source = File(safe.getString("input_file")).canonicalFile
                val imports = File(context.filesDir,"imports").canonicalFile
                require(source.parentFile == imports && source.isFile && source.length() <= 256L*1024*1024) { "Invalid imported capture" }
                check(source.renameTo(File(job,"input.pcap"))){"Could not move the imported capture into its session"}
                safe.remove("input_file")
            }
            if(operation=="delete_capture") {
                val target=safe.getString("capture_session").removePrefix("imported:")
                require(RadioAssessment.eligibleOrigin(target)){"Invalid capture session"}
                write(File(job,"delete-target"),target)
            }
            write(File(job,"request.json"),safe.toString())
            write(File(job,"journal.json"),JSONObject().put("schema_version",1).put("id",id).put("operation",operation).put("state","prepared").put("created_ms",System.currentTimeMillis()).put("replayed",false).put("supervisor_started",false).put("recovery_required_before",mutable.value.recoveryRequired).put("privacy_pending_before",mutable.value.privacyPending).toString())
            current = job
            queuedOperation = operation
            if(operation=="wps")WpsProgressStore.begin(id)
            mutable.value = RadioUiState(true,"Preparing $operation",id,mutable.value.recoveryRequired,mutable.value.identityReport,mutable.value.privacyReport,mutable.value.privacyPending,operation,recoveryChecked=mutable.value.recoveryChecked)
            context.startForegroundService(Intent(context,RadioService::class.java))
        } catch (e: Exception) {
            val job = current
            if (job != null && queuedOperation.isNotEmpty()) {
                val record = read(File(job,"journal.json")).put("state","failed_before_launch")
                runCatching { write(File(job,"journal.json"),record.toString()) }
            }
            current = null; queuedOperation = ""
            mutable.value = mutable.value.copy(busy=false,phase=e.message ?: "Unable to start")
        }
    }
    fun startAnalysis(uri: Uri)=copyForAnalysis {requireNotNull(context.contentResolver.openInputStream(uri)){"Cannot open capture"}}
    @Synchronized internal fun copyForAnalysis(open:()->java.io.InputStream) {
        if (!::context.isInitialized || mutable.value.busy) return
        if (bluetoothOwner != null) { mutable.value = mutable.value.copy(phase="Stop the Bluetooth operation before importing a capture"); return }
        val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
        importCancellation=cancelled
        mutable.value=mutable.value.copy(busy=true,phase="Copying selected capture",operation="import",captureReady=false)
        scope.launch {
            var file: File? = null
            try {
                val imports = File(context.filesDir,"imports").apply { mkdirs() }
                val imported = File(imports,UUID.randomUUID().toString()+".pcap")
                file = imported
                open().use { input ->
                    importInput=input
                    try {imported.outputStream().use { output ->copyCapture(input,output,cancelled::get)}}
                    finally {importInput=null}
                }
                synchronized(this@RadioController) {
                    check(!cancelled.get()){"Capture import cancelled"}
                    importCancellation=null
                    mutable.value=mutable.value.copy(busy=false,operation=null)
                    start("analyze",JSONObject().put("input_file",imported.absolutePath))
                }
            } catch (e: Exception) {
                // Release the temporary file before publishing idle. Otherwise a
                // state observer (or process death) can outrun the finally block.
                val removed=file?.let {!it.exists()||it.delete()}?:true
                val message=if(cancelled.get())"Capture import cancelled" else e.message?:"Capture import failed"
                synchronized(this@RadioController) {
                    importCancellation=null
                    mutable.value=mutable.value.copy(busy=false,operation=null,phase=message+if(removed)"" else " · temporary copy needs cleanup")
                }
            } finally {file?.delete()}
        }
    }
    @Synchronized internal fun executePending(finishService:()->Unit) {
        val job=current ?: return
        val operation=queuedOperation
        if(operation.isEmpty())return
        queuedOperation=""
        scope.launch { execute(operation,job,finishService) }
    }
    private fun copyAssets(path: String, dest: File) {
        val children=context.assets.list(path).orEmpty()
        if(children.isEmpty()) { dest.parentFile?.mkdirs();context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } } }
        else { dest.mkdirs();children.forEach { copyAssets("$path/$it",File(dest,it)) } }
    }
    private fun quote(value: String) = "'" + value.replace("'","'\\''") + "'"
    private data class TransportOutcome(val code: Int, val error: String?, val started: Boolean)
    private suspend fun transport(operation: String,job: File): TransportOutcome {
        var code=-1
        var failure:String?=null
        var started=false
        var wpsBridge:LiveWpsBridge?=null
        var wpsTask:Job?=null
        try {
            copyAssets("runtime",tools)
            val request=read(File(job,"request.json"))
            if(operation=="wps") {
                val bridge=LiveWpsBridge(context,job.name,request.getString("bssid"),request.optString("strategy","pixie"))
                wpsBridge=bridge
                request.put("wps_socket",bridge.name).put("privacy_profile","wps")
                write(File(job,"request.json"),request.toString())
                wpsTask=scope.launch {bridge.run()}
            }
            val journal=read(File(job,"journal.json")).put("state","running")
            write(File(job,"journal.json"),journal.toString())
            LabRepository.get(context).recordExperiment(job.name,operation,"running",journal.toString(),request)
            val command=if(operation=="setup") {
                mutable.value=mutable.value.copy(phase="Preparing bundled runtime")
                val (archive,manifest)=RuntimePackage.stage(context,File(job,"cancel"))
                val required=(manifest.getLong("installed_bytes")+manifest.getLong("archive_bytes")+128L*1024*1024)/1024+1
                "/system/bin/sh ${quote(File(tools,"runtime-installer.sh").absolutePath)} ${quote(job.name)} ${quote(job.absolutePath)} ${android.os.Process.myPid()} ${quote(archive.absolutePath)} ${quote(manifest.getString("sha256"))} $required"
            } else "/system/bin/sh ${quote(File(tools,"qca-runner.sh").absolutePath)} ${quote(operation)} ${quote(job.name)} ${quote(job.absolutePath)} ${android.os.Process.myPid()} ${quote(request.optString("bssid","all"))} ${quote(File(context.applicationInfo.nativeLibraryDir,"libbender_wpa.so").absolutePath)}"
            // Persist launch intent before starting root, so process death cannot erase it.
            write(File(job,"journal.json"),journal.put("supervisor_started",true).toString())
            val process=ProcessBuilder("su","-mm","-c",command).redirectErrorStream(true).start()
            started=true
            val budget=when(operation){"setup"->600L;"capture"->360L;"wps"->420L;else->240L}
            val deadline=timer.schedule({ runCatching { write(File(job,"cancel"),"deadline\n") } },budget,TimeUnit.SECONDS)
            val reader=scope.launch {
                try {
                var bytes=0
                process.inputStream.bufferedReader().useLines { lines ->
                    File(job,"supervisor.log").bufferedWriter().use { log ->
                        lines.forEach { line ->
                            // Never interpret capture text as a command or record base64 payloads in logs.
                            if(line.startsWith("PHASE:") || line.startsWith("ERROR:") || line.startsWith("READY:") || line.startsWith("EXIT:")) {
                                val safe=line.take(1000)
                                if(safe.startsWith("ERROR:"))failure=safe.substringAfter(':')
                                if(bytes<128000){log.appendLine(safe);log.flush();bytes+=safe.length}
                                synchronized(this@RadioController) {
                                    if(safe.startsWith("PHASE:Scanning")&&(File(job,"finish").exists()||File(job,"cancel").exists()))return@forEach
                                    val phase=if(request.optString("purpose")=="handshake"&&safe.startsWith("PHASE:Scanning")) "Listening · reconnect your owned client now" else safe.substringAfter(':')
                                    mutable.value=mutable.value.copy(phase=phase,captureReady=operation=="capture"&&safe.startsWith("PHASE:Scanning"))
                                    if(operation=="wps"&&safe.startsWith("PHASE:"))WpsProgressStore.supervisor(job.name,phase)
                                    RadioService.update(context,mutable.value.phase)
                                }
                            }
                        }
                    }
                }
                }catch(e:CancellationException){throw e}
                catch(_:java.io.IOException){
                    // Closing a pipe during cancellation must not crash the app.
                    // The result and recovery journals still determine completion.
                    if(process.isAlive && !File(job,"cancel").exists())failure="Supervisor status stream closed unexpectedly; inspect Evidence"
                }
            }
            try {
                if(!process.waitFor(budget+90L,TimeUnit.SECONDS)) {
                    write(File(job,"cancel"),"transport_timeout\n")
                    process.destroy()
                    failure="Supervisor timed out; outcome unknown"
                } else code=process.exitValue()
            } finally {
                deadline.cancel(false)
                // Drain a completed supervisor's final error/exit lines before
                // recording its result. Closing immediately can lose the cause.
                withTimeoutOrNull(5_000){reader.join()}
                runCatching {process.inputStream.close()}
                reader.cancelAndJoin()
            }
        } catch(e:Exception) { failure=e.message?.take(300) ?: e.javaClass.simpleName }
        finally {wpsBridge?.close();wpsTask?.cancelAndJoin()}
        return TransportOutcome(code,failure,started)
    }
    private suspend fun execute(operation: String,job: File,finishService:()->Unit) {
        val previousRecovery=read(File(job,"journal.json")).opt("recovery_required_before")==true
        var recovery=previousRecovery
        var phase="Evidence could not be saved · inspect this session before continuing"
        var identityReport=mutable.value.identityReport
        var privacyReport=mutable.value.privacyReport
        val previousPrivacy=read(File(job,"journal.json")).opt("privacy_pending_before")==true
        var privacyPending=previousPrivacy
        var launched=false
        try {
            val transport=transport(operation,job)
            launched=transport.started
            val result=read(File(job,"result.json"))
            val runtime=read(File(job,"runtime-state.json"))
            val radio=read(File(job,"radio-state.json"))
            recovery=RadioAssessment.recoveryAfter(previousRecovery,operation,launched,radio)
            val outcome=RadioAssessment.completion(transport.code,result,recovery,operation,File(job,"attempt.json").length()>0,launched)
            runtimeMutable.value=RadioAssessment.runtimeAfter(runtimeMutable.value,operation,outcome,runtime)
            write(File(job,"exit.json"),JSONObject().put("code",transport.code).put("error",transport.error).toString())
            val identity=read(File(job,"identity-state.json"))
            if(identity.has("allowed")) identityReport=identity.toString()
            val privacy=read(File(job,"privacy-state.json"))
            if(privacy.has("pending")) privacyReport=privacy.toString()
            privacyPending=RadioAssessment.privacyAfter(previousPrivacy,operation,launched,privacy,radio)
            val observation=File(job,"observations-v1.json")
            val importFailure=importSavedObservations(job,operation,LabRepository.get(context)::importObservations)
            val request=read(File(job,"request.json"))
            val journal=read(File(job,"journal.json")).put("state",outcome).put("finished_ms",System.currentTimeMillis()).put("supervisor_started",launched).put("recovery_required_after",recovery).put("import_error",importFailure)
            val radioNote=RadioAssessment.radioDescription(radio)
            phase=when {
                recovery -> "Unresolved radio state · verify / restore Wi-Fi"
                outcome=="cancelled" -> "Cancelled · $radioNote"
                outcome=="uncertain" -> "Submission uncertain · no automatic retry"
                operation=="wps" && outcome!="completed" -> {
                    val reason=WpsMessages.failures[result.optString("error_code")]?:transport.error?:"WPS did not complete"
                    val at=WpsMessages.title(result.optString("failed_phase","setup"))
                    "$reason · stage: $at · $radioNote"
                }
                operation=="identity" -> if(identity.opt("allowed")==true) "Strict-profile snapshot passed" else "Strict-profile snapshot · automatic test setup runs when you start"
                operation in setOf("prepare","privacy_restore","privacy_status") -> privacy.optString("message").ifBlank { "Connectivity profile unverified · check status or use Android settings" }
                outcome!="completed" -> transport.error ?: "Operation failed · inspect Evidence"
                operation=="setup" -> runtime.optString("message","Runtime installed · check the radio next")
                operation=="recovery_status" -> if(privacyPending)"Root journals checked · connectivity restoration pending" else "Root journals checked · no saved recovery pending"
                operation in setOf("delete_capture","cleanup_captures") -> "Raw capture cleanup completed · summaries retained"
                importFailure!=null -> "Observation import needs attention · $radioNote"
                read(File(job,"retention-state.json")).opt("runtime_capture_retained")==true -> "Runtime capture copy retained · inspect session evidence before deleting copies"
                operation=="analyze" -> "Capture analysis completed"
                operation=="capture" && observation.length()==0L -> "Capture saved · protocol analysis unavailable; inspect Evidence"
                operation=="lab" -> if(result.optBoolean("matching_ack"))"Driver ACK recorded · client effect requires observation" else "Request finished · inspect transmission evidence"
                operation=="wps" -> when(result.optString("state")) {
                    "credential_received" -> "WPS password saved in Vault · $radioNote"
                    "pin_not_found" -> "No PIN found in this bounded search · $radioNote"
                    "common_pins_rejected" -> "Three PIN candidates rejected · $radioNote"
                    else -> "WPS finished · inspect Saved work · $radioNote"
                }
                else -> "$operation completed · $radioNote"
            }
            if(operation=="wps")WpsProgressStore.finish(job.name,phase)?.let {write(File(job,"wps-progress.json"),it.json().toString())}
            val evidence=sessionEvidence(job,outcome,importFailure)
            LabRepository.get(context).recordExperiment(job.name,operation,outcome,evidence.toString(),request)
            write(File(job,"journal.json"),journal.toString())
        } catch (_: Exception) {
            recovery=RadioAssessment.recoveryAfter(previousRecovery,operation,launched,read(File(job,"radio-state.json")))
            privacyPending=RadioAssessment.privacyAfter(previousPrivacy,operation,launched,read(File(job,"privacy-state.json")),read(File(job,"radio-state.json")))
            runCatching {
                val journal=read(File(job,"journal.json")).put("state","evidence_error").put("supervisor_started",launched).put("recovery_required_after",recovery)
                write(File(job,"journal.json"),journal.toString())
            }
        } finally {
            if(operation=="wps")runCatching {
                WpsProgressStore.finish(job.name,phase)?.let {write(File(job,"wps-progress.json"),it.json().toString())}
            }
            // Storage or Room failure must not strand the foreground service or busy latch.
            withContext(Dispatchers.Main.immediate) {
                runCatching { finishRadioSession(finishService) {
                    synchronized(this@RadioController) {current=null;mutable.value=RadioUiState(false,phase,job.name,recovery,identityReport,privacyReport,privacyPending,recoveryChecked=RadioAssessment.rootStateObserved(read(File(job,"radio-state.json"))))}
                } }
            }
        }
    }
    @Synchronized fun finishCapture() {
        val job=current?:return
        if(mutable.value.operation!="capture"||!mutable.value.captureReady)return
        runCatching {write(File(job,"finish"),"user_finish\n")}.onSuccess {
            mutable.value=mutable.value.copy(phase="Finishing capture · saving and analyzing",captureReady=false)
        }.onFailure {mutable.value=mutable.value.copy(phase="Could not request finish; retry or cancel")}
    }
    @Synchronized fun cancel() {
        importCancellation?.let {
            it.set(true);mutable.value=mutable.value.copy(phase="Cancelling capture import")
            importInput?.let {input->scope.launch {runCatching {input.close()}}}
            return
        }
        val job=current ?: return
        runCatching {
            write(File(job,"cancel"),"user_cancel\n")
            val journal=read(File(job,"journal.json")).put("state","cancel_requested")
            write(File(job,"journal.json"),journal.toString())
        }
        mutable.value=mutable.value.copy(phase="Stopping · waiting for restoration",captureReady=false)
        if(mutable.value.operation=="wps")WpsProgressStore.event(job.name,"restoring","Cancellation requested; waiting for radio cleanup")
    }
}
