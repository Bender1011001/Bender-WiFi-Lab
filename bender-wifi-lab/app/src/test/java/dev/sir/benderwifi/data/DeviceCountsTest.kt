package dev.sir.benderwifi.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceCountsTest {
    private fun network(raw:String)=NetworkEntity("ap:02:00:00:00:00:01","02:00:00:00:00:01","Lab","unknown",1,2412,null,"unknown",1.0,20.0,"synthetic",raw)

    @Test fun apTotalIsSeparateFromIdentifiedClientsAndKeepsZero() {
        for(count in listOf(0,4,65535)) {
            val raw=JSONObject().put("bss_load",JSONObject().put("station_count",count).put("frame",5).put("time",10))
            val cleaned=ObservationSchema().clean(raw)
            assertEquals(AdvertisedStationCount(count,10.0),network(cleaned.toString()).advertisedStationCount())
            assertEquals(count,ShareRedactor().clean(raw).getJSONObject("bss_load").getInt("station_count"))
        }
    }

    @Test fun missingOrMalformedReportIsUnknownNeverZero() {
        for(raw in listOf("{}","bad json","""{"bss_load":{"station_count":4}}""","""{"bss_load":{"time":10}}"""))
            assertNull(network(raw).advertisedStationCount())
        for(value in listOf<Any>(-1,65536,1.5,"4")) {
            val raw=JSONObject().put("bss_load",JSONObject().put("station_count",value).put("time",10))
            assertNull(network(raw.toString()).advertisedStationCount())
            assertNull(network(ObservationSchema().clean(raw).toString()).advertisedStationCount())
        }
    }

    @Test fun controlPeerProvenanceSurvivesImportAndRedaction() {
        val raw=JSONObject("""{"kind":"observed_control_peer","frame_subtype":27,"ap_id":"ap:02:00:00:00:00:01","time":10,"frame":5}""")
        for(result in listOf(ObservationSchema().clean(raw),ShareRedactor().clean(raw))) {
            assertEquals("observed_control_peer",result.getString("kind"))
            assertEquals(27,result.getInt("frame_subtype"))
            assertTrue(result.has("ap_id"))
        }
    }
}
