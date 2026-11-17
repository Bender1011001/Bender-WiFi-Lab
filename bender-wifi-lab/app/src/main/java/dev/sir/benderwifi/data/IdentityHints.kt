package dev.sir.benderwifi.data

import org.json.JSONArray
import org.json.JSONObject

data class IdentityHint(val kind:String,val value:String,val time:Double,val frame:Long,val session:String?=null) {
    fun json():JSONObject=JSONObject().put("hint",kind).put("value",value).put("time",time).put("frame",frame).apply {session?.let {put("session_id",it)}}
}

/** Only bounded device identity metadata is retained privately. No arbitrary TXT,
 * DHCP option blobs, requested SSIDs, serial numbers, credentials or packet payloads. */
object IdentityHints {
    const val MAX=24
    val kinds=setOf("wps_name","wps_manufacturer","wps_model","wps_model_number","wps_type","dhcp_hostname","dhcp_vendor","mdns_service")
    private val service=Regex("_[a-z0-9_-]{1,48}\\._(?:tcp|udp)\\.local")
    fun readable(value:String,max:Int=160):Boolean=value.isNotBlank() && value.length<=max && value.codePoints().noneMatch {
        Character.getType(it) in setOf(Character.CONTROL.toInt(),Character.FORMAT.toInt(),Character.SURROGATE.toInt(),Character.LINE_SEPARATOR.toInt(),Character.PARAGRAPH_SEPARATOR.toInt())
    }
    fun read(array:JSONArray?):List<IdentityHint> {
        if(array==null)return emptyList()
        return (0 until minOf(array.length(),MAX)).mapNotNull {i->
            val row=array.optJSONObject(i)?:return@mapNotNull null
            val kind=row.optString("hint");val value=(row.opt("value") as? String)?.trim()?:return@mapNotNull null
            val time=(row.opt("time") as? Number)?.toDouble()?:return@mapNotNull null
            val frame=(row.opt("frame") as? Number)?.toDouble()?:return@mapNotNull null
            if(kind !in kinds || !readable(value) || !time.isFinite() || time<0 || !frame.isFinite() || frame<1 || frame>100_000_000 || frame!=frame.toLong().toDouble())return@mapNotNull null
            if(kind=="wps_type" && !value.matches(Regex("[0-9a-f]{4}0050f204[0-9a-f]{4}")))return@mapNotNull null
            if(kind=="mdns_service" && !service.matches(value))return@mapNotNull null
            IdentityHint(kind,value,time,frame.toLong(),(row.opt("session_id") as? String)?.takeIf {readable(it,256)})
        }.distinctBy {it.kind to it.value}
    }
    fun clean(array:JSONArray):JSONArray=JSONArray().apply {read(array).forEach {put(it.json())}}
    fun fromJson(raw:String):List<IdentityHint> = runCatching {read(JSONObject(raw).optJSONArray("identity_hints"))}.getOrDefault(emptyList())
    fun merge(previous:String?,current:JSONObject,session:String,preferCurrent:Boolean):JSONObject {
        val old=previous?.let(::fromJson).orEmpty()
        val fresh=read(current.optJSONArray("identity_hints")).map {it.copy(session=session)}
        val combined=(if(preferCurrent)fresh+old else old+fresh).distinctBy {it.kind to it.value}.take(MAX)
        val selected=if(!preferCurrent && previous!=null)JSONObject(previous) else JSONObject(current.toString())
        if(combined.isNotEmpty())selected.put("identity_hints",JSONArray().apply {combined.forEach {put(it.json())}})
        return selected
    }
}
