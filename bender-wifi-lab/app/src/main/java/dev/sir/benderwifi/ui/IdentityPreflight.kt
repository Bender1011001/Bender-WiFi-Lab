package dev.sir.benderwifi.ui

import org.json.JSONObject

internal data class IdentityCheck(val id:String,val status:String,val reason:String)
internal data class IdentityPreflight(val allowed:Boolean,val checkedAt:Double?,val checks:List<IdentityCheck>,val blocker:String?)
private val requiredIdentityChecks=setOf("android_context","airplane_mode","mobile_data","bluetooth","wlan_mac","assigned_addresses","ipv4_routes","ipv6_routes","cellular_links")

/** A UI snapshot is informational; the root runner must repeat all checks before transmission. */
internal fun identityPreflight(report:String?,now:Double=System.currentTimeMillis()/1000.0):IdentityPreflight {
    val missing="Identity state is unknown. Run the read-only identity check before a lab test."
    if(report==null)return IdentityPreflight(false,null,emptyList(),missing)
    return runCatching {
        val root=JSONObject(report)
        val schema=root.opt("schema_version")
        require(schema is Number && schema.toDouble() in setOf(1.0,2.0))
        val required=if(schema.toDouble()==2.0){
            require(root.optString("profile")=="wifi")
            setOf("android_context","wlan_mac","wlan_ipv4","wlan_ipv6")
        }else requiredIdentityChecks
        val checkedAt=(root.opt("checked_at") as? Number)?.toDouble()?.takeIf {it.isFinite()&&it>0}
        val entries=root.getJSONArray("checks")
        val checks=(0 until entries.length()).map {index->entries.getJSONObject(index).let {IdentityCheck(it.getString("id"),it.getString("status"),it.optString("reason","No reason supplied"))}}
        val fresh=checkedAt!=null&&now-checkedAt in 0.0..120.0
        val checkIds=checks.map {it.id}
        val passing=root.opt("allowed")==true&&checkIds.size==required.size&&checkIds.toSet()==required&&checks.all {it.status=="pass"}
        val blocker=when {
            !fresh->"Identity check is stale or has no valid timestamp. Run it again."
            !passing->"Identity prerequisites are blocked or unknown. Resolve every check before a lab test."
            else->null
        }
        IdentityPreflight(fresh&&passing,checkedAt,checks,blocker)
    }.getOrElse {IdentityPreflight(false,null,emptyList(),"Identity report is invalid. Run the read-only identity check again.")}
}
