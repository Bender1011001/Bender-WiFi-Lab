package dev.sir.benderwifi.radio

import org.json.JSONObject
import java.io.File

/** Ingest committed decoder output only. Never starts or repeats radio work. */
internal suspend fun importSavedObservations(job:File,operation:String,ingest:suspend (String,String)->Unit):String? {
    if(operation !in setOf("capture","analyze")) return null
    val artifact=File(job,"observations-v1.json")
    if(artifact.length()==0L) return null
    return try {
        require(artifact.length()<=10_000_000) {"Observation file exceeds the import limit"}
        val state=File(job,"analysis-state.json")
        require(state.length() in 1..64_000 && JSONObject(state.readText()).optString("status")=="complete") {"Saved analysis completion is unverified"}
        ingest(artifact.readText(),if(operation=="analyze")"imported:${job.name}" else job.name)
        null
    } catch(e:Exception) {e.message?.take(200) ?: "Observation import failed"}
}
