package dev.sir.benderwifi

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sir.benderwifi.data.ClientEntity
import dev.sir.benderwifi.data.NetworkEntity
import dev.sir.benderwifi.ui.CatalogAction
import dev.sir.benderwifi.ui.WifiNetworks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic inventory with distinct AP identities, shared SSID and an unlinked client. */
class WifiInventoryTest {
    @get:Rule val compose=createComposeRule()
    private val first=ap("01",2412,1).copy(rawJson="""{"bss_load":{"station_count":4,"time":1000,"frame":1}}""")
    private val second=ap("02",5180,36)
    private val linked=client("03",first.id)
    private val unknown=client("04",null)
    private var radioRequests=0

    private fun ap(end:String,freq:Int,ch:Int)=NetworkEntity("ap:02:00:00:00:00:$end","02:00:00:00:00:$end","SAME NAME","WPA2",ch,freq,-50,"unknown",1000.0,1000.0,"synthetic","{}")
    private fun client(end:String,ap:String?)=ClientEntity("sta:02:00:00:00:00:$end","02:00:00:00:00:$end",ap,-60,1000.0,1000.0,"synthetic","{}")

    private fun show(catalog:List<CatalogAction> = emptyList(), unavailable:(CatalogAction)->Unit = {}) {
        compose.setContent {
            var network by remember {mutableStateOf<NetworkEntity?>(null)}
            var device by remember {mutableStateOf<ClientEntity?>(null)}
            MaterialTheme {WifiNetworks(listOf(first,second),listOf(linked,unknown),network,device,catalog,false,false,
                onNetwork={id->network=listOf(first,second).find {it.id==id};device=null},
                onDevice={id->device=listOf(linked,unknown).find {it.id==id};if(id!=null)network=listOf(first,second).find {it.id==device?.apId}},
                onScan={radioRequests++},onTools={},onCapture={radioRequests++},onHandshake={radioRequests++},onDeauth={radioRequests++},
                onEvidence={},onUnavailable=unavailable,savedWork={})}
        }
    }

    @Test fun sameNamedApsKeepSeparateCountsAndFiltersSurviveBack() {
        show()
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${first.id}"))
        compose.onNodeWithTag("network:${first.id}").assert(hasText("1 device address identified · -50 dBm"))
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${second.id}"))
        compose.onNodeWithTag("network:${second.id}").assert(hasText("No device addresses identified · -50 dBm"))
        compose.onNodeWithTag("network-list").performScrollToNode(hasText("5 GHz"))
        compose.onNodeWithText("5 GHz").performClick()
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${second.id}"))
        compose.onNodeWithTag("network:${first.id}").assertDoesNotExist()
        compose.onNodeWithTag("network:${second.id}").performClick()
        compose.onNodeWithText("Devices (0)").performClick()
        compose.onNodeWithText("Look for devices").assertExists()
        compose.onNodeWithText("‹ All networks").performClick()
        compose.onNodeWithTag("network-list").performScrollToNode(hasText("5 GHz"))
        compose.onNodeWithText("5 GHz").assertIsSelected()
        compose.runOnIdle {assertEquals(0,radioRequests)}
    }

    @Test fun unlinkedDeviceHasEvidenceButNoApTransmissionAction() {
        show()
        compose.onNodeWithTag("network-list").performScrollToNode(hasText("Show unlinked devices (1)"))
        compose.onNodeWithText("Show unlinked devices (1)").performClick()
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("device:${unknown.id}"))
        compose.onNodeWithTag("device:${unknown.id}").performClick()
        compose.onNodeWithText("Network relationship unknown",substring=true).assertExists()
        compose.onNodeWithText("Deauth selected device").assertDoesNotExist()
        compose.onNodeWithText("Capture network handshake").assertDoesNotExist()
        compose.onNodeWithText("Raw device evidence").assertExists()
        compose.runOnIdle {assertEquals(0,radioRequests)}
    }

    @Test fun apTotalIsSeparateAndDiscoveryRemainsAvailableWithExistingClients() {
        show()
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${first.id}"))
        compose.onNodeWithTag("network:${first.id}").assert(hasText("AP reported 4 connected stations at",substring=true))
        compose.onNodeWithTag("network:${first.id}").assert(hasText("1 device address identified · -50 dBm"))
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${second.id}"))
        compose.onNodeWithTag("network:${second.id}").assert(hasText("Connected-device total unknown · no AP count captured"))
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${first.id}"))
        compose.onNodeWithTag("network:${first.id}").performClick()
        compose.onNodeWithText("Devices (1)").performClick()
        compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("Look for devices"))
        compose.onNodeWithText("Look for devices").performClick()
        compose.runOnIdle {assertEquals(1,radioRequests)}
    }

    @Test fun unsupportedActionExplainsBlockerWithoutSendingAnything() {
        var opened:String?=null
        val action=CatalogAction("wps-pin","WPS PIN audit",listOf("ap"),"unavailable","WPS assessment","Transport unqualified",null)
        show(listOf(action)){opened=it.blocker}
        compose.onNodeWithTag("network-list").performScrollToNode(hasTestTag("network:${first.id}"))
        compose.onNodeWithTag("network:${first.id}").performClick()
        compose.onNodeWithTag("network-detail-list").performScrollToNode(hasText("Why unavailable"))
        compose.onNodeWithText("Not implemented").assertExists()
        compose.onNodeWithText("Why unavailable").performClick()
        compose.runOnIdle {assertEquals("Transport unqualified",opened);assertEquals(0,radioRequests)}
    }
}
