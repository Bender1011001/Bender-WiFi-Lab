package dev.sir.benderwifi.data

import org.junit.Assert.*
import org.junit.Test

class ScanInventoryTest {
    private fun capture(id:String,time:Long,state:String="completed")=ExperimentEntity(id,"capture",state,"""{"request":{"purpose":"survey"}}""",time)
    private fun session(id:String)=SessionEntity(id,"runtime capture",10,"{}","[]")
    private val network="""{"id":"ap:02:00:00:00:00:01","bssid":"02:00:00:00:00:01","ssid_display":"CURRENT","last_seen":1}"""
    private val client="""{"id":"sta:02:00:00:00:00:02","mac":"02:00:00:00:00:02","ap_id":"ap:02:00:00:00:00:01","last_seen":1}"""

    @Test fun latestLiveAttemptIgnoresImportsAndOtherActions() {
        val latest=capture("new",20,"running")
        val imported=capture("imported:future",900)
        val analysis=ExperimentEntity("analysis","analyze","completed","{}",999)
        assertEquals(latest,ScanInventory.latestCapture(listOf(capture("old",10),latest,imported,analysis)))
    }

    @Test fun snapshotUsesOnlyItsOwnMembershipAndAPRelationship() {
        val rows=listOf(ObservationEntity(sessionId="new",targetId=null,kind="network",rawJson=network,recordedAt=1),
            ObservationEntity(sessionId="old",targetId=null,kind="client",rawJson=client,recordedAt=999))
        val value=ScanInventory.from(capture("new",20),session("new"),rows)
        assertEquals("CURRENT",value.networks.single().ssidDisplay)
        assertTrue(value.clients.isEmpty())
        assertNull(value.networks.single().signalDbm)
        assertTrue(value.isSurvey)
    }

    @Test fun emptyCompletedCaptureDoesNotBecomeMissingOrBorrowOldResults() {
        val value=ScanInventory.from(capture("empty",30),session("empty"),emptyList())
        assertNotNull(value.session)
        assertTrue(value.networks.isEmpty());assertTrue(value.clients.isEmpty())
    }

    @Test fun missingOrWrongSessionKeepsFailedAttemptWithoutStaleResults() {
        val attempt=capture("failed",30,"failed")
        val value=ScanInventory.from(attempt,session("old"),emptyList())
        assertEquals("failed",value.attempt!!.state)
        assertNull(value.session);assertTrue(value.networks.isEmpty())
    }

    @Test fun knownDeviceCannotSupplyPresenceOrOldNetworkRelationship() {
        val old=ClientEntity("sta:02:00:00:00:00:02","02:00:00:00:00:02","ap:02:00:00:00:00:99",-10,0.0,999.0,"old","{}")
        val row=ObservationEntity(sessionId="new",targetId=null,kind="client",rawJson=client,recordedAt=1)
        val value=ScanInventory.from(capture("new",20),session("new"),listOf(row),listOf(old))
        assertEquals("ap:02:00:00:00:00:01",value.clients.single().apId)
        assertNull(value.clients.single().signalDbm)
        assertEquals(1.0,value.clients.single().lastSeen,0.0)
        assertTrue(ScanInventory.from(capture("new",20),session("new"),emptyList(),listOf(old)).clients.isEmpty())
    }
}
