package dev.sir.benderwifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.*
import dev.sir.benderwifi.ui.LabApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Room and production UI with synthetic captures; no radio requests. */
class LatestScanWorkflowTest {
    @get:Rule val compose=createComposeRule()
    private val ap="ap:02:00:00:00:00:71"
    private fun fixture(label:String,client:String="72",extra:Boolean=false)="""{"schema_version":1,"networks":[{"id":"$ap","bssid":"02:00:00:00:00:71","ssid_display":"$label","frequency_mhz":2437,"last_seen":10}${if(extra)",{\"id\":\"ap:02:00:00:00:00:79\",\"bssid\":\"02:00:00:00:00:79\",\"ssid_display\":\"OLD ONLY\",\"last_seen\":10}" else ""}],"clients":[{"id":"sta:02:00:00:00:00:$client","mac":"02:00:00:00:00:$client","ap_id":"$ap","last_seen":10}]}"""
    private suspend fun record(repo:LabRepository,id:String,time:Long,state:String="completed") {
        repo.recordExperiment(id,"capture",state,"{}",JSONObject().put("purpose","survey"),time)
    }

    @Test fun currentSnapshotSurvivesImportsAndEmptyFailedScansKeepHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            repo.importObservations(fixture("OLD","72",true),"bw_1000_000000000001");record(repo,"bw_1000_000000000001",1000)
            repo.importObservations(fixture("CURRENT","73"),"bw_2000_000000000001");record(repo,"bw_2000_000000000001",2000)
            var current=repo.latestScan.first()
            assertEquals(listOf("CURRENT"),current.networks.map {it.ssidDisplay})
            assertEquals(listOf("02:00:00:00:00:73"),current.clients.map {it.mac})
            assertEquals(2,repo.networks.first().size);assertEquals(2,repo.clients.first().size)
            repo.importObservations(fixture("IMPORTED FUTURE","74"),"imported:future")
            repo.recordExperiment("imported:future","analyze","completed","{}",createdAt=9000)
            current=repo.latestScan.first()
            assertEquals("CURRENT",current.networks.single().ssidDisplay)
            assertEquals(1,current.clients.size)
            record(repo,"bw_3000_000000000001",3000,"running")
            assertNull(repo.latestScan.first().session)
            record(repo,"bw_3000_000000000001",3000,"failed")
            assertEquals("failed",repo.latestScan.first().attempt!!.state)
            assertEquals(2,repo.networks.first().size)
            repo.importObservations("""{"schema_version":1,"networks":[],"clients":[]}""","bw_4000_000000000001")
            record(repo,"bw_4000_000000000001",4000)
            current=repo.latestScan.first()
            assertNotNull(current.session);assertTrue(current.networks.isEmpty());assertTrue(current.clients.isEmpty())
            assertEquals(2,repo.networks.first().size);assertEquals(3,repo.clients.first().size)
        } finally {db.close()}
    }

    @Test fun latestAndHistoryTabsScopeNetworkCountsAndDeviceNavigation() {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        val repo=LabRepository(db)
        try {
            runBlocking {
                repo.importObservations(fixture("OLD","72",true),"bw_1000_000000000001");record(repo,"bw_1000_000000000001",1000)
                repo.importObservations(fixture("CURRENT","73"),"bw_2000_000000000001");record(repo,"bw_2000_000000000001",2000)
            }
            compose.setContent {LabApp(repo)}
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("1 network · 1 observed device address"))
            compose.onNodeWithText("1 network · 1 observed device address").assertIsDisplayed()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("CURRENT"))
            compose.onNodeWithText("OLD ONLY").assertDoesNotExist()
            compose.onNodeWithText("CURRENT").performClick()
            compose.onNodeWithText("Devices (1)").performClick()
            compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("02:00:00:00:00:73"))
            compose.onNodeWithText("02:00:00:00:00:72").assertDoesNotExist()
            compose.onNodeWithText("02:00:00:00:00:73").performClick()
            compose.onNodeWithTag("device-detail-list").performScrollToNode(hasText("‹ Network devices"))
            compose.onNodeWithText("‹ Network devices").performClick()
            compose.onNodeWithText("‹ All networks").performClick()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("Saved history"))
            compose.onNodeWithText("Saved history").performClick()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("2 networks · 2 saved device addresses"))
            compose.onNodeWithText("2 networks · 2 saved device addresses").assertIsDisplayed()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("OLD ONLY"))
            compose.onNodeWithText("OLD ONLY").assertIsDisplayed()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("Latest scan"))
            compose.onNodeWithText("Latest scan").performClick()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("1 network · 1 observed device address"))
            saveBluetoothScreenshot("latest-scan-synthetic")
        } finally {db.close()}
    }
}
