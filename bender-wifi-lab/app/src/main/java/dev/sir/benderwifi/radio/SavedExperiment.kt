package dev.sir.benderwifi.radio

import dev.sir.benderwifi.data.LabRepository
import org.json.JSONObject
import java.io.File

internal fun readSessionJson(file:File):JSONObject=try {
    if(file.length()>4_000_000)JSONObject() else JSONObject(file.readText())
}catch(_:Exception){JSONObject()}

internal fun sessionEvidence(job:File,outcome:String,importError:String?=null):JSONObject {
    fun read(name:String)=readSessionJson(File(job,name))
    return JSONObject().put("schema_version",1).put("execution",outcome)
        .put("request",read("request.json")).put("result",read("result.json"))
        .put("attempt",read("attempt.json")).put("radio",read("radio-state.json"))
        .put("identity",read("identity-state.json")).put("privacy",read("privacy-state.json")).put("privacy_preparation",read("privacy-preparation.json"))
        .put("analysis",read("analysis-state.json"))
        .put("retention",read("retention-state.json"))
        .put("wps_progress",read("wps-progress.json"))
        .put("transport_error",read("exit.json").opt("error")).put("import_error",importError)
}

/** Terminal files and Room are separate commits: replay this upsert, never an operation. */
internal suspend fun reconcileSavedExperiment(job:File,operation:String,outcome:String,importError:String?,repo:LabRepository) {
    val evidence=sessionEvidence(job,outcome,importError)
    val created=readSessionJson(File(job,"journal.json")).optLong("created_ms").takeIf {it>0}
    repo.recordExperiment(job.name,operation,outcome,evidence.toString(),evidence.getJSONObject("request"),created,true)
}
