package dev.sir.benderwifi.data

import org.json.JSONArray
import org.json.JSONObject

/** Explicit typed projection. Unknown fields and free text cannot enter imported
 * observations or shared evidence. Private SSID fields are intentional. Sharing
 * reprojects old records too, so pre-upgrade imports cannot bypass this policy. */
internal class ObservationSchema(private val sharing:Boolean=false) {
    private val aliases=linkedMapOf<String,String>()
    private fun alias(value:String,prefix:String)=aliases.getOrPut("$prefix:$value"){"${prefix}_${aliases.size+1}"}
    private val mac=Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    private val runtimeSession=Regex("bw_[0-9]+_[0-9a-f]{12}")
    private val numbers=setOf("schema_version","export_schema_version","frame_count","frame","frame_subtype","time","first_seen","last_seen","recorded_at","created_at","imported_at","frequency_mhz","channel","signal_dbm","privacy","ie_index","version","capabilities","declared_akm_count","declared_pairwise_count","type","link_id","status_code","message","replay_counter","failed","verified","not_flagged","unknown")
    private val booleans=setOf("redacted","ssid_present","mfpc","mfpr","present","security_conflict","pmf_advertisement_conflict","password_verified","mic_verified","ok","cancelled","submission_may_have_occurred","radio_mutated","matching_ack","restoration_verified","replayed","runtime_capture_retained","handoff_failed","raw_capture_deletion_complete")
    private val objects=setOf("source","wps","mlo","bss_load","security_summary","frame_integrity","evidence","result","request","radio","identity","privacy_state","retention")
    private val objectArrays=setOf("networks","clients","handshakes","observations","experiments","advertisements","security_ies","nested_profile_security_ies","advertised_akms","akms","pairwise_ciphers","group_ciphers","group_management_ciphers","relations","frames","security_variants","ie_groups")
    private val numericArrays=setOf("messages","owner_tag_path")
    private val textArrays=setOf("protocols","authentication","pmf","pmf_observed_states")
    private val akms=setOf("802.1X","PSK","FT-802.1X","FT-PSK","802.1X-SHA256","PSK-SHA256","SAE","FT-SAE","802.1X-Suite-B","802.1X-Suite-B-192","OWE","unknown")
    private val ciphers=setOf("Use-group","WEP-40","TKIP","CCMP-128","WEP-104","BIP-CMAC-128","GCMP-128","GCMP-256","CCMP-256","BIP-GMAC-128","BIP-GMAC-256","BIP-CMAC-256","unknown")
    private val pmfs=setOf("required","capable","disabled","unknown")
    private val labels=setOf("WPA","WPA2","WPA3","WPA2-Personal","WPA3-Personal","WPA2/WPA3-Personal","RSN-Enterprise","RSN (unknown AKM)","OWE","Open","Privacy set (protocol unknown)","unknown","PSK","SAE","PSK/SAE")
    fun clean(root:JSONObject):JSONObject=project(root,0)
    private fun project(root:JSONObject,depth:Int):JSONObject {
        require(depth<=48){"Evidence nesting exceeds limit"}
        val out=JSONObject()
        root.keys().forEach {key->
            val value=root.opt(key)
            if(!sharing && key in setOf("first_seen","last_seen","time") && value!=null && value!=JSONObject.NULL) {
                require(value is Number && value.toDouble().isFinite() && value.toDouble()>=0){"Invalid observation timestamp"}
            }
            val clean:Any?=when {
                key=="station_count" -> (value as? Number)?.takeIf {it.toDouble().isFinite() && it.toDouble() in 0.0..65535.0 && it.toDouble()==it.toLong().toDouble()}
                key in numbers -> (value as? Number)?.takeIf {it.toDouble().isFinite()}
                key in booleans -> value as? Boolean
                key in setOf("bssid","mac","ap","client","mld_mac","link_bssid","logical_source","logical_destination") -> (value as? String)?.takeIf {mac.matches(it)}?.lowercase()?.let {if(sharing)alias(it,"radio") else it}
                key in setOf("id","ap_id","client_id","target_id","session_id") -> identifier(key,value)
                key in objects && (value is JSONObject || value is String && key!="frame_integrity") -> objectValue(value)?.let {project(it,depth+1)}
                key in objectArrays -> (value as? JSONArray)?.let {array->JSONArray().also {target->for(i in 0 until array.length()) {
                    val item=array.opt(i)
                    if(item is JSONObject)target.put(project(item,depth+1))
                    else if(key in setOf("pairwise_ciphers","group_ciphers")&&item is String&&item in ciphers)target.put(item)
                }}}
                key in numericArrays -> (value as? JSONArray)?.let {array->JSONArray().also {target->for(i in 0 until array.length())(array.opt(i) as? Number)?.takeIf {it.toDouble().isFinite()}?.let {target.put(it)}}}
                key in textArrays -> if(value is String)text(key,value) else (value as? JSONArray)?.let {array->JSONArray().also {target->for(i in 0 until array.length())text(key,array.opt(i))?.let {target.put(it)}}}
                key in setOf("ssid_display","ssid_hex") && !sharing -> (value as? String)?.takeIf {if(key=="ssid_hex")it.length<=64&&it.matches(Regex("[0-9a-fA-F]*")) else it.length<=256}
                key=="raw_hex" && !sharing -> (value as? String)?.takeIf {it.matches(Regex("[0-9a-fA-F]{8}"))}
                key=="oui" -> (value as? String)?.takeIf {it.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){2}"))}
                key=="artifact_sha256" -> (value as? String)?.takeIf {it.matches(Regex("[0-9a-f]{64}"))}
                key in setOf("wps.version","wps.config_methods","wps.selected_registrar","wps.wifi_protected_setup_state","wps.ap_setup_locked") -> (value as? JSONArray)?.let {array->JSONArray().also {target->for(i in 0 until array.length())(array.opt(i) as? String)?.takeIf {it.matches(Regex("(?:0x[0-9a-fA-F]{1,8}|[0-9]{1,5}|True|False)"))}?.let {target.put(it)}}}
                else -> text(key,value)
            }
            if(clean!=null)out.put(key,clean)
        }
        return out
    }
    private fun objectValue(value:Any?):JSONObject?=when(value){is JSONObject->value;is String->runCatching {EvidenceJson.parseSanitized(value)}.getOrNull();else->null}
    private fun identifier(key:String,value:Any?):String? {
        if(value !is String||value.length>256)return null
        val prefix=value.substringBefore(':',"");val address=value.substringAfter(':',"")
        if(prefix in setOf("ap","sta")&&mac.matches(address))return "$prefix:${if(sharing)alias(address.lowercase(),"radio") else address.lowercase()}"
        if(key=="session_id"||key=="id") {
            if(sharing) {
                val origin=value.removePrefix("imported:")
                if(runtimeSession.matches(origin))return alias(origin,"session")
                return alias(value,if(key=="session_id")"session" else "record")
            }
            if(value.matches(Regex("eapol:[0-9]+|(?:imported:)?bw_[0-9]+_[0-9a-f]{12}")))return value
        }
        return null
    }
    private fun text(key:String,value:Any?):String? {
        if(value !is String)return null
        val allowed=when(key){
            "name","authentication"->akms+ciphers
            "protocol","protocols"->setOf("WPA","RSN")
            "pmf","pmf_advertised","pmf_observed_states","negotiated_pmf"->pmfs
            "negotiated_security"->setOf("unknown")
            "parse_status","quality","security_quality"->setOf("decoded","incomplete","malformed","partial","conflicting","unknown")
            "coverage"->setOf("partial","four_messages_observed")
            "frame_integrity"->setOf("failed","verified","not_flagged","unknown")
            "kind"->setOf("network","client","handshakes","observations","malformed_frame","excluded_bad_fcs","association_request","association_response","observed_addressing","observed_control_peer")
            "scope"->setOf("nested_profile_identity_unresolved")
            "relationship_scope"->setOf("advertised_common_info_only")
            "status"->setOf("decoded_common_info","unknown","complete","failed","cancelled")
            "decoder"->setOf("tshark-pdml")
            "operation","action"->setOf("setup","probe","capture","lab","restore","analyze","identity","prepare","privacy_restore","privacy_status","cleanup_captures","recovery_status")
            "state"->setOf("prepared","running","completed","complete","failed","failed_before_launch","cancelled","uncertain","recovery_required","idle","ready","pending","unknown")
            "security","label"->return value.takeIf {s->s.removePrefix("Conflicting: ").split(" | "," + ").all {it in labels}}
            else->emptySet()
        }
        return value.takeIf {it in allowed}
    }
}
