package dev.sir.benderwifi.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShareRedactorTest {
    @Test fun redactsIdentifiersPacketBytesPathsAndNestedJsonResults(){
        val mac="02:00:00:00:00:01"
        val root=JSONObject().put("bssid",mac).put("target_id","ap:$mac").put("ssid_display","PRIVATE NETWORK")
            .put("result",JSONObject().put("ap",mac).put("frame_hex","deadbeef").put("password","SECRET").put("capture_file","/data/user/0/private.pcap").toString())
        val output=ShareRedactor().clean(root)
        val encoded=output.toString()
        listOf(mac,"PRIVATE NETWORK","deadbeef","SECRET","/data/user").forEach {assertFalse(encoded.contains(it))}
        assertEquals("ap:${output.getString("bssid")}",output.getString("target_id"))
        assertEquals(output.getString("bssid"),output.getJSONObject("result").getString("ap"))
    }
}
