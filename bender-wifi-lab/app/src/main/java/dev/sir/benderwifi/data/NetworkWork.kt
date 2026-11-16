package dev.sir.benderwifi.data

import org.json.JSONObject

/** Identity-based workspace links. SSID display names never join unrelated APs. */
object NetworkWork {
    private val address=Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    fun networkId(value:String?):String? {
        val mac=value?.removePrefix("ap:")?:return null
        return if(address.matches(mac)) "ap:${mac.lowercase()}" else null
    }
    fun networkFromObservation(kind:String,value:JSONObject):String? = when(kind){
        "network" -> networkId(value.optString("id"))
        "client","handshakes" -> networkId(value.optString("ap_id"))
        else -> null
    }
    fun networkFromRequest(request:JSONObject):String? = networkId(request.optString("bssid"))?:networkId(request.optString("ap"))
    fun origins(session:String):Set<String> = if(session.startsWith("imported:bw_")&&session.removePrefix("imported:").matches(Regex("bw_[0-9]+_[a-f0-9]{12}"))) setOf(session,session.removePrefix("imported:")) else setOf(session)
    fun handshakeLabel(json:String):String {
        val value=runCatching {JSONObject(json)}.getOrNull()?:return "Unrecognized exchange"
        val messages=value.optJSONArray("messages")?:return "No numbered messages"
        val observed=(0 until messages.length()).map {messages.optInt(it)}.filter {it in 1..4}.distinct().sorted()
        return if(observed.isEmpty()) "No numbered messages" else observed.joinToString(" · "){"M$it"}
    }
}
