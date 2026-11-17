package dev.sir.benderwifi

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.*
import dev.sir.benderwifi.ui.WifiNetworks
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic observations only. These tests do not request a scan or transmit. */
class DeviceIdentityWorkflowTest {
    @get:Rule val compose=createComposeRule()
    private val apId="ap:02:00:00:00:00:01"
    private val deviceMac="02:00:00:00:00:02"
    private val hints=JSONArray().put(IdentityHint("wps_model","Lab TV",1000.0,3,"bw_1000_000000000001").json())
        .put(IdentityHint("wps_type","00070050f2040001",1000.0,3,"bw_1000_000000000001").json())
    private fun fixture(time:Int,withHints:Boolean,mac:String=deviceMac,ap:String=apId):String {
        val client=JSONObject().put("id","sta:$mac").put("mac",mac).put("ap_id",ap).put("last_seen",time)
        if(withHints)client.put("identity_hints",hints)
        return JSONObject().put("schema_version",1).put("networks",JSONArray().put(JSONObject().put("id",ap).put("bssid",ap.removePrefix("ap:")).put("ssid_display","SYNTHETIC LAB").put("last_seen",time)))
            .put("clients",JSONArray().put(client)).toString()
    }

    @Test fun packagedManufacturerTableLoadsAndResolvesAnExistingSavedAddress() {
        val index=DeviceVendors.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("Roku, Inc.",index.lookup("00:0d:4b:00:00:01")?.name)
        assertEquals("Espressif Inc.",index.lookup("00:4b:12:00:00:01")?.name)
        assertNull(index.lookup("02:0d:4b:00:00:01"))
        assertEquals("Roku, Inc.",DeviceIdentity.guess("00:0d:4b:00:00:01","{}",index).title)
    }

    @Test fun repositoryPreservesIdentityAcrossSparseScansWithoutMovingOrMergingDevices()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            repo.importObservations(fixture(1000,true),"bw_1000_000000000001")
            repo.importObservations(fixture(2000,false),"bw_2000_000000000001")
            repo.importObservations(fixture(3000,true,ap="ap:02:00:00:00:00:09"),"imported:old")
            val selected=repo.clients.first().single()
            assertEquals(apId,selected.apId);assertEquals("bw_2000_000000000001",selected.sessionId)
            assertEquals("Lab TV",DeviceIdentity.guess(selected.mac,selected.rawJson,null).title)
            assertEquals("bw_1000_000000000001",IdentityHints.fromJson(selected.rawJson).first().session)
            repo.importObservations(fixture(4000,true,mac="02:00:00:00:00:03"),"bw_4000_000000000001")
            assertEquals(2,repo.clients.first().size)
            assertTrue(repo.clients.first().all {DeviceIdentity.guess(it.mac,it.rawJson,null).title=="Lab TV"})
        } finally {db.close()}
    }

    @Test fun networkDeviceRowOpensReportedIdentityAndCaptureReasons() {
        val network=NetworkEntity(apId,apId.removePrefix("ap:"),"SYNTHETIC LAB","WPA2",36,5180,-50,"unknown",1000.0,1000.0,"synthetic","{}")
        val client=ClientEntity("sta:$deviceMac",deviceMac,apId,-60,1000.0,1000.0,"synthetic",JSONObject().put("identity_hints",hints).toString())
        var requests=0
        compose.setContent {
            var device by remember {mutableStateOf<ClientEntity?>(null)}
            MaterialTheme {WifiNetworks(listOf(network),listOf(client),network,device,emptyList(),false,false,
                onNetwork={},onDevice={device=if(it==client.id)client else null},onScan={requests++},onTools={},onCapture={requests++},onHandshake={requests++},onDeauth={requests++},onEvidence={},onUnavailable={},savedWork={})}
        }
        compose.onNodeWithText("Devices (1)").performClick()
        compose.onNodeWithTag("network-detail-list").performScrollToNode(hasTestTag("device:${client.id}"))
        compose.onNodeWithTag("device:${client.id}").assert(hasText("Lab TV"))
        compose.onNodeWithTag("device:${client.id}").assert(hasText("TV / display · Device-reported"))
        compose.onNodeWithTag("device:${client.id}").performClick()
        compose.onNodeWithText("Best available device guess").assertIsDisplayed()
        compose.onNodeWithText("Why this guess (2 clues)").performClick()
        compose.onNodeWithTag("device-detail-list").performScrollToNode(hasText("WPS model: Lab TV",substring=true))
        compose.onNodeWithText("WPS model: Lab TV",substring=true).assertExists()
        compose.onAllNodesWithText("Source session: bw_1000_000000000001",substring=true).assertCountEquals(2)
        compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap->
            val file=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"validation/device-identity.png")
            file.parentFile!!.mkdirs();file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        }
        compose.onNodeWithTag("device-detail-list").performScrollToNode(hasText("Raw device evidence"))
        compose.onNodeWithText("Raw device evidence").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,requests)}
    }
}
