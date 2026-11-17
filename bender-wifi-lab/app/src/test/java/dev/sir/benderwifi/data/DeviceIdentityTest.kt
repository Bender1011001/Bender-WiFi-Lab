package dev.sir.benderwifi.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    private val index=VendorIndex.read("24\t001122\tModule vendor\n28\t0011223\tBoard vendor\n36\t001122334\tRoku, Inc.\n".reader())
    private fun hint(kind:String,value:String,time:Double=1000.0,frame:Long=3)=IdentityHint(kind,value,time,frame).json()
    private fun raw(vararg hints:JSONObject)=JSONObject().put("identity_hints",JSONArray(hints.toList()))
    private fun guess(vararg hints:JSONObject)=DeviceIdentity.guess("00:11:22:33:44:55",raw(*hints).toString(),index)

    @Test fun lookupUsesLongestPrefixAndNeverClearsLocalOrGroupBits() {
        assertEquals(VendorMatch("Roku, Inc.",36),index.lookup("00:11:22:33:44:55"))
        assertEquals(VendorMatch("Board vendor",28),index.lookup("00:11:22:3a:44:55"))
        assertEquals(VendorMatch("Module vendor",24),index.lookup("00:11:22:aa:44:55"))
        listOf("02:11:22:33:44:55","01:11:22:33:44:55","00:00:00:00:00:00","001122334455","invalid").forEach {assertNull(index.lookup(it))}
    }
    @Test fun existingSavedDeviceGetsVendorClueWithoutAnyNewCapture() {
        val result=DeviceIdentity.guess("00:11:22:33:44:55","{}",index)
        assertEquals("Roku, Inc.",result.title)
        assertEquals("TV / streamer (possible)",result.type)
        assertEquals("Possible",result.confidence)
        assertTrue(result.basis.contains("retail brand or model may differ"))
    }
    @Test fun localAddressCanHaveReportedModelButNeverAVendorGuess() {
        val result=DeviceIdentity.guess("02:11:22:33:44:55",raw(hint("wps_model","Lab TV"),hint("wps_type","00070050f2040001")).toString(),index)
        assertEquals("Lab TV",result.title);assertEquals("TV / display",result.type)
        assertEquals("Device-reported",result.confidence);assertTrue(result.localAddress);assertNull(result.vendor)
    }
    @Test fun hostnameDoesNotMakeInferredTypeIndependentlyVerified() {
        val result=guess(hint("dhcp_hostname","Living-room"),hint("mdns_service","_googlecast._tcp.local"))
        assertEquals("Living-room",result.title);assertEquals("Cast / media device (possible)",result.type)
        assertEquals("Device-reported",result.confidence)
        assertTrue(result.basis.contains("not independently verified"))
    }
    @Test fun conflictingModelsOrTypesRemainVisible() {
        assertTrue(guess(hint("wps_model","TV One"),hint("wps_model","Phone Two")).conflict)
        assertTrue(guess(hint("wps_type","00070050f2040001"),hint("wps_type","000a0050f2040001")).conflict)
        assertFalse(guess(hint("wps_model","TV One"),hint("wps_model","tv one")).conflict)
    }
    @Test fun softwareAndServicesCannotProduceExactProductModels() {
        val android=guess(hint("dhcp_vendor","android-dhcp-16"))
        assertEquals("Android-based device",android.title);assertEquals("Possible",android.confidence)
        assertEquals("Android-based device (possible)",android.type)
        assertEquals("Printer / print server (possible)",guess(hint("mdns_service","_ipp._tcp.local")).type)
    }
    @Test fun identityProjectionIsBoundedTypedAndRejectsDisplayControls() {
        val malformed=listOf(hint("serial","SERIAL"),hint("wps_model","a\u202eb"),hint("wps_name","a\u2028b"),
            hint("wps_model","x".repeat(161)),hint("wps_type","0007001122040001"),hint("mdns_service","personal-name.local"),
            hint("wps_name","negative time",-1.0),hint("wps_name","bad frame").put("frame",1.5))
        assertEquals(0,IdentityHints.fromJson(raw(*malformed.toTypedArray()).toString()).size)
        val good=hint("wps_name","Lab television").put("serial","SECRET")
        val clean=ObservationSchema().clean(raw(good)).toString()
        assertTrue(clean.contains("Lab television"));assertFalse(clean.contains("SECRET"))
        assertEquals(24,IdentityHints.read(JSONArray((1..100).map {hint("dhcp_hostname","host-$it")})).size)
    }
    @Test fun sharingOmitsAllPrivateIdentityHintsEvenFromLegacyNestedEvidence() {
        val private=raw(hint("wps_name","PRIVATE NAME").put("session_id","PRIVATE SESSION"),hint("mdns_service","_ipp._tcp.local"))
        val export=ShareRedactor().clean(JSONObject().put("evidence",private).put("raw_json",private.toString())).toString()
        listOf("PRIVATE","identity_hints","_ipp").forEach {assertFalse(export.contains(it))}
    }
    @Test fun laterSparseScanRetainsIdentityAndItsActualOrigin() {
        val origin=IdentityHints.merge(null,raw(hint("wps_model","Lab TV").put("session_id","forged")),"bw_1000_000000000001",true)
        val merged=IdentityHints.merge(origin.toString(),JSONObject().put("ap_id","ap:02:00:00:00:00:02"),"bw_2000_000000000001",true)
        assertEquals("ap:02:00:00:00:00:02",merged.getString("ap_id"))
        assertEquals("bw_1000_000000000001",IdentityHints.fromJson(merged.toString()).single().session)
    }
    @Test fun historicalClueCannotReplaceSelectedRelationshipOrNewerClueOrigin() {
        val live=IdentityHints.merge(null,raw(hint("wps_model","Lab TV")).put("ap_id","ap:02:00:00:00:00:01"),"bw_2000_000000000001",true)
        val historical=raw(hint("wps_model","Lab TV",4102444800.0)).put("ap_id","ap:02:00:00:00:00:02")
        val merged=IdentityHints.merge(live.toString(),historical,"imported:old",false)
        assertEquals("ap:02:00:00:00:00:01",merged.getString("ap_id"))
        assertEquals("bw_2000_000000000001",IdentityHints.fromJson(merged.toString()).single().session)
    }
}
