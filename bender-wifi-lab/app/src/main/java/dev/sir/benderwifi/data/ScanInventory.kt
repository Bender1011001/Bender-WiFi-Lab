package dev.sir.benderwifi.data

import org.json.JSONObject

/** One live capture's observations. The accumulated inventory is kept separately. */
data class ScanInventory(
    val attempt:ExperimentEntity?=null,
    val session:SessionEntity?=null,
    val networks:List<NetworkEntity> = emptyList(),
    val clients:List<ClientEntity> = emptyList()
) {
    val isSurvey:Boolean get()=runCatching {
        JSONObject(attempt?.result?:"{}").optJSONObject("request")?.optString("purpose")=="survey"
    }.getOrDefault(false)
    companion object {
        fun latestCapture(experiments:List<ExperimentEntity>):ExperimentEntity? = experiments
            .filter { it.operation=="capture" && !it.id.startsWith("imported:") }
            .maxWithOrNull(compareBy<ExperimentEntity> {it.createdAt}.thenBy {it.id})

        fun from(attempt:ExperimentEntity?,session:SessionEntity?,observations:List<ObservationEntity>,knownClients:List<ClientEntity> = emptyList()):ScanInventory {
            if(attempt==null || session==null || session.id!=attempt.id) return ScanInventory(attempt)
            val networks=linkedMapOf<String,NetworkEntity>()
            val clients=linkedMapOf<String,ClientEntity>()
            val history=knownClients.associateBy {it.id}
            for(row in observations) {
                if(row.sessionId!=session.id)continue
                val value=runCatching {JSONObject(row.rawJson)}.getOrNull()?:continue
                val id=value.optString("id")
                if(row.kind=="network") {
                    val bssid=value.optString("bssid")
                    if(NetworkWork.networkId(bssid)!=id)continue
                    networks[id]=NetworkEntity(id,bssid,value.optString("ssid_display").ifBlank {"Hidden / unknown SSID"},
                        value.optString("security","unknown"),value.optionalInt("channel"),value.optionalInt("frequency_mhz"),
                        value.optionalInt("signal_dbm"),value.optString("pmf_advertised","unknown"),
                        value.optDouble("first_seen",0.0),value.optDouble("last_seen",0.0),session.id,row.rawJson)
                } else if(row.kind=="client") {
                    val mac=value.optString("mac")
                    if(NetworkWork.networkId(mac)==null || id!="sta:${mac.lowercase()}")continue
                    // Retain qualified identity clues with their original provenance;
                    // only this capture supplies presence, AP relationship and signal.
                    val identity=IdentityHints.merge(history[id]?.rawJson,value,session.id,true)
                    clients[id]=ClientEntity(id,mac,NetworkWork.networkId(value.optString("ap_id")),
                        value.optionalInt("signal_dbm"),value.optDouble("first_seen",0.0),value.optDouble("last_seen",0.0),session.id,identity.toString())
                }
            }
            return ScanInventory(attempt,session,networks.values.toList(),clients.values.toList())
        }

        private fun JSONObject.optionalInt(key:String):Int? = if(has(key)&&!isNull(key))optInt(key) else null
    }
}
