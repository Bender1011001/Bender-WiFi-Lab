package dev.sir.benderwifi.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IdentityPreflightTest {
    private val now=1_800_000_000.0
    private val ids=listOf("android_context","airplane_mode","mobile_data","bluetooth","wlan_mac","assigned_addresses","ipv4_routes","ipv6_routes","cellular_links")
    private fun report(status:String="pass",allowed:Boolean=true,time:Double=now)=JSONObject()
        .put("schema_version",1).put("checked_at",time).put("allowed",allowed)
        .put("checks",JSONArray(ids.map {JSONObject().put("id",it).put("status",status).put("reason","Synthetic test")}))
    @Test fun missingMalformedUnknownOrBlockedFactsCannotPass(){
        assertFalse(identityPreflight(null,now).allowed)
        assertFalse(identityPreflight("invalid",now).allowed)
        assertFalse(identityPreflight(report("unknown").toString(),now).allowed)
        assertFalse(identityPreflight(report(allowed=false).toString(),now).allowed)
        assertFalse(identityPreflight(report().put("checks",JSONArray()).toString(),now).allowed)
        assertFalse(identityPreflight(report().put("checks",JSONArray().put(JSONObject().put("id","wlan_mac").put("status","pass"))).toString(),now).allowed)
        assertFalse(identityPreflight(report().put("schema_version",2).toString(),now).allowed)
    }
    @Test fun staleMissingOrFutureTimestampBlocksEvenAnAllowedReport(){
        assertFalse(identityPreflight(report(time=now-121).toString(),now).allowed)
        assertFalse(identityPreflight(report(time=now+1).toString(),now).allowed)
        assertFalse(identityPreflight(report().also {it.remove("checked_at")}.toString(),now).allowed)
    }
    @Test fun freshPassingSnapshotOnlyEnablesTheUiGate(){
        val state=identityPreflight(report().toString(),now)
        assertTrue(state.allowed)
        assertNull(state.blocker)
        assertFalse(identityPreflight(report().toString(),now+121).allowed)
    }
    @Test fun wirelessPolicyIsVersionedAndRequiresBothFilters(){
        val checks=org.json.JSONArray()
        for(id in listOf("android_context","wlan_mac","wlan_ipv4","wlan_ipv6"))checks.put(JSONObject().put("id",id).put("status","pass"))
        val result=JSONObject().put("schema_version",2).put("profile","wifi").put("checked_at",now).put("allowed",true).put("checks",checks)
        assertTrue(identityPreflight(result.toString(),now).allowed)
        assertFalse(identityPreflight(result.put("profile","unknown").toString(),now).allowed)
        result.put("profile","wifi");checks.getJSONObject(3).put("status","unknown")
        assertFalse(identityPreflight(result.toString(),now).allowed)
    }
    @Test fun coercibleStringsAndAmbiguousCheckSetsAreRejected(){
        assertFalse(identityPreflight(report().put("allowed","true").toString(),now).allowed)
        assertFalse(identityPreflight(report().put("schema_version","1").toString(),now).allowed)
        assertFalse(identityPreflight(report().put("schema_version",1.1).toString(),now).allowed)
        assertFalse(identityPreflight(report().put("checked_at",now.toString()).toString(),now).allowed)
        val duplicate=report().also {it.getJSONArray("checks").put(JSONObject().put("id",ids.first()).put("status","pass"))}
        assertFalse(identityPreflight(duplicate.toString(),now).allowed)
        val unknown=report().also {it.getJSONArray("checks").put(JSONObject().put("id","future_unknown_check").put("status","pass"))}
        assertFalse(identityPreflight(unknown.toString(),now).allowed)
        val replacement=report().also {it.getJSONArray("checks").put(0,JSONObject().put("id",ids.last()).put("status","pass"))}
        assertFalse(identityPreflight(replacement.toString(),now).allowed)
    }
}
