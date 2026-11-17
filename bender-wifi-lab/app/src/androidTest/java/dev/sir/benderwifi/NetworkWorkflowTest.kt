package dev.sir.benderwifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.LabDatabase
import dev.sir.benderwifi.data.LabRepository
import dev.sir.benderwifi.ui.LabApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The production screen with an isolated database. No root/radio action runs. */
class NetworkWorkflowTest {
    @get:Rule val compose=createComposeRule()

    @Test fun selectedNetworkConnectsCaptureDeauthAndSavedWork() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val repo=LabRepository(db)
        val ap="ap:02:00:00:00:00:31"
        val other="ap:02:00:00:00:00:33"
        val fixture="""{"schema_version":1,"networks":[{"id":"$ap","bssid":"02:00:00:00:00:31","ssid_display":"WORKSPACE ONE","frequency_mhz":2437,"channel":6,"last_seen":1000},{"id":"$other","bssid":"02:00:00:00:00:33","ssid_display":"WORKSPACE TWO","frequency_mhz":2437,"channel":6,"last_seen":999}],"clients":[{"id":"sta:02:00:00:00:00:32","mac":"02:00:00:00:00:32","ap_id":"$ap","last_seen":1000}],"handshakes":[{"ap_id":"$ap","client_id":"sta:02:00:00:00:00:32","messages":[1,2],"coverage":"partial","password_verified":false}]}"""
        try {
            runBlocking {
                repo.importObservations(fixture,"imported:workflow-fixture")
                repo.recordExperiment("fixture-action","lab","completed","{}",JSONObject().put("ap",ap.removePrefix("ap:")))
                repo.saveNote(other,"Other network note")
            }
            compose.setContent {LabApp(repo)}
            compose.waitForIdle()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("Saved history"))
            compose.onNodeWithText("Saved history").performClick()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("2 networks · 1 saved device address"))
            compose.onNodeWithText("2 networks · 1 saved device address").assertIsDisplayed()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("WORKSPACE ONE"))
            saveBluetoothScreenshot("wifi-networks-synthetic")
            compose.onNodeWithText("WORKSPACE ONE").performClick()
            compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("Capture handshake"))
            saveBluetoothScreenshot("wifi-actions-synthetic")
            compose.onNodeWithText("Capture handshake").performClick()
            compose.onNodeWithText("Capture authentication on this network").assertIsDisplayed()
            compose.onNodeWithText("Start listening").assertIsEnabled()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Devices (1)").performClick()
            compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("02:00:00:00:00:32"))
            compose.onNodeWithText("02:00:00:00:00:32").performClick()
            compose.onNodeWithTag("device-detail-list").performScrollToNode(hasText("Deauth selected device"))
            saveBluetoothScreenshot("wifi-device-synthetic")
            compose.onNodeWithText("Deauth selected device").performClick()
            compose.onNodeWithText("Directed deauth test").assertIsDisplayed()
            compose.onNodeWithText("Run bounded test").assertExists() // Opening the dialog never prepares or transmits.
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("device-detail-list").performScrollToNode(hasText("‹ Network devices"))
            compose.onNodeWithText("‹ Network devices").performClick()
            compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("1 identified address · latest saved relationships"))
            compose.onNodeWithText("1 identified address · latest saved relationships").assertIsDisplayed()
            compose.onNodeWithText("Saved work").performClick()
            compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("Saved work for this network"))
            compose.onNodeWithText("1 capture records · 1 handshake exchanges · 1 actions").assertExists()
            compose.onNodeWithText("Other network note").assertDoesNotExist()
            compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("Network notes"))
            compose.onNodeWithText("Network notes").performTextInput("Kept with network one")
            compose.onNodeWithText("Save notes").performScrollTo().performClick()
            compose.waitUntil(10_000){runBlocking {repo.networkNote(ap).first()?.text=="Kept with network one"}}
            assertEquals("Other network note",runBlocking {repo.networkNote(other).first()!!.text})
        } finally {db.close()}
    }
}
