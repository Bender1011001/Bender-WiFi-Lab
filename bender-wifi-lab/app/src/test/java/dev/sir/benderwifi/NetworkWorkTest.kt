package dev.sir.benderwifi

import dev.sir.benderwifi.data.NetworkWork
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkWorkTest {
    @Test fun linksUseAddressesAndNeverSsidNames(){
        assertNull(NetworkWork.networkFromRequest(JSONObject("""{"ssid":"same name","bssid":"all"}""")))
        assertEquals("ap:02:aa:00:00:00:01",NetworkWork.networkFromRequest(JSONObject("""{"bssid":"02:AA:00:00:00:01"}""")))
        assertNotEquals(NetworkWork.networkId("02:00:00:00:00:01"),NetworkWork.networkId("02:00:00:00:00:02"))
    }
    @Test fun importedCaptureLinksToItsRunWithoutBecomingLive(){
        val imported="imported:bw_123_000000000001"
        assertEquals(setOf(imported,"bw_123_000000000001"),NetworkWork.origins(imported))
        assertEquals(setOf("imported:uuid"),NetworkWork.origins("imported:uuid"))
        assertEquals(setOf("imported:bw_invalid"),NetworkWork.origins("imported:bw_invalid"))
    }
    @Test fun messagesRemainCoverageRatherThanPasswordVerification(){
        assertEquals("M1 · M2",NetworkWork.handshakeLabel("""{"messages":[2,1,2,0,5],"password_verified":false}"""))
        assertEquals("M1 · M2 · M3 · M4",NetworkWork.handshakeLabel("""{"messages":[1,2,3,4]}"""))
        assertEquals("Unrecognized exchange",NetworkWork.handshakeLabel("malformed"))
    }
}
