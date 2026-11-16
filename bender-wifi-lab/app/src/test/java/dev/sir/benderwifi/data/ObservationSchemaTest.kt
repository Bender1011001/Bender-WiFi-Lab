package dev.sir.benderwifi.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationSchemaTest {
    @Test fun exportPreservesExperimentObservationSessionJoins() {
        val source="""{"observations":[{"session_id":"bw_123_012345abcdef"},{"session_id":"imported:bw_123_012345abcdef"}],"experiments":[{"id":"bw_123_012345abcdef"}]}"""
        val shared=ShareRedactor().clean(JSONObject(source))
        val id=shared.getJSONArray("experiments").getJSONObject(0).getString("id")
        for(i in 0..1)assertEquals(id,shared.getJSONArray("observations").getJSONObject(i).getString("session_id"))
    }
    @Test fun invalidTimestampsFailImportButAreOmittedFromShare() {
        for(value in listOf("NaN","Infinity","bad",-1)) {
            val raw=JSONObject().put("last_seen",value)
            assertTrue(runCatching {ObservationSchema().clean(raw)}.isFailure)
        }
        assertFalse(ShareRedactor().clean(JSONObject().put("last_seen","bad")).has("last_seen"))
    }
    @Test fun importedUnknownAliasesAndFreeTextCannotBecomeStoredObservations() {
        val root=JSONObject("""{"schema_version":1,"networks":[{"id":"ap:02:00:00:00:00:01","bssid":"02:00:00:00:00:01","ssid_display":"KNOWN NAME","wifi_key":"SECRET KEY","essid":"ALTERNATE NAME","notes":"PRIVATE NOTE","security":"SENSITIVE TEXT","advertisements":[{"time":1,"wifi_key":"NESTED SECRET"}]}],"source":{"decoder":"tshark-pdml","wifi_key":"SOURCE SECRET"}}""")
        val cleaned=ObservationSchema().clean(root)
        val encoded=cleaned.toString()
        listOf("SECRET","ALTERNATE","PRIVATE","SENSITIVE","wifi_key","essid").forEach {assertFalse(encoded.contains(it))}
        assertEquals("KNOWN NAME",cleaned.getJSONArray("networks").getJSONObject(0).getString("ssid_display"))
        assertEquals(1,cleaned.getJSONArray("networks").getJSONObject(0).getJSONArray("advertisements").getJSONObject(0).getInt("time"))
    }
    @Test fun legacyRecordsAndAllowedKeyTypeConfusionCannotLeakThroughExport() {
        val raw=JSONObject("""{"session_id":"PRIVATE SESSION","target_id":"ap:02:00:00:00:00:01","kind":"network","evidence":{"bssid":"02:00:00:00:00:01","ssid_display":"KNOWN NAME","wifi_key":"SECRET KEY","essid":"ALTERNATE NAME","signal_dbm":"SECRET NUMBER","ok":"SECRET BOOLEAN","protocol":"SECRET PROTOCOL","coverage":"SECRET COVERAGE","source":{"frame_count":"SECRET COUNT"},"result":"{\"wifi_key\":\"EMBEDDED SECRET\"}"}}""")
        val encoded=ShareRedactor().clean(raw).toString()
        listOf("PRIVATE","SECRET","ALTERNATE","KNOWN NAME","02:00:00:00:00:01").forEach {assertFalse(encoded.contains(it))}
        assertTrue(encoded.contains("radio_"));assertTrue(encoded.contains("session_"))
    }
    @Test fun typedSecurityAndIntegritySurvivePrivateAndSharedProjection() {
        val raw=JSONObject("""{"security":"WPA2/WPA3-Personal","security_quality":"decoded","security_variants":[{"label":"WPA2/WPA3-Personal","protocols":["RSN"],"authentication":["PSK","SAE"],"pairwise_ciphers":["CCMP-128"],"group_ciphers":["CCMP-128"],"pmf":["capable"],"quality":"decoded","privacy":1}],"source":{"frame_integrity":{"failed":2,"verified":0,"not_flagged":3,"unknown":1}}} """)
        for(cleaned in listOf(ObservationSchema().clean(raw),ShareRedactor().clean(raw))) {
            assertEquals("WPA2/WPA3-Personal",cleaned.getString("security"))
            assertEquals("CCMP-128",cleaned.getJSONArray("security_variants").getJSONObject(0).getJSONArray("pairwise_ciphers").getString(0))
            assertEquals(2,cleaned.getJSONObject("source").getJSONObject("frame_integrity").getInt("failed"))
        }
    }
    @Test fun selectorsRetainMetadataButSharingExcludesRawSelectorBytes() {
        val raw=JSONObject("""{"pairwise_ciphers":[{"oui":"00:50:f2","type":4,"name":"CCMP-128","raw_hex":"0050f204"}]}""")
        assertTrue(ObservationSchema().clean(raw).toString().contains("0050f204"))
        assertFalse(ShareRedactor().clean(raw).toString().contains("0050f204"))
    }
}
