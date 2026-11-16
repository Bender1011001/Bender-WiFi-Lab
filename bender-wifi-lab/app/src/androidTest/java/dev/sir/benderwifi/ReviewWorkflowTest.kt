package dev.sir.benderwifi

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sir.benderwifi.ui.CaptureControls
import dev.sir.benderwifi.ui.CaptureExport
import dev.sir.benderwifi.ui.SetupPanel
import dev.sir.benderwifi.ui.SessionCaptureExport
import dev.sir.benderwifi.radio.RadioUiState
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewWorkflowTest {
    @get:Rule val compose=createComposeRule()
    @Test fun finishingAndCancellingAreSeparateAndFinishRequiresListening(){
        val ready=mutableStateOf(false);var finished=0;var cancelled=0
        compose.setContent {MaterialTheme {CaptureControls(true,ready.value,{finished++},{cancelled++})}}
        compose.onNodeWithText("Finish & save").assertDoesNotExist()
        compose.runOnIdle {ready.value=true}
        compose.onNodeWithText("Finish & save").performClick()
        compose.runOnIdle {assertEquals(1,finished);assertEquals(0,cancelled)}
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {assertEquals(1,finished);assertEquals(1,cancelled)}
    }
    @Test fun rawCaptureExportDoesNotRequireANetworkRecord(){
        var exports=0
        compose.setContent {MaterialTheme {CaptureExport(true){exports++}}}
        compose.onNodeWithText("Raw packets include network identifiers and may include authentication traffic.").assertIsDisplayed()
        compose.onNodeWithText("Export private PCAP").performClick()
        compose.runOnIdle {assertEquals(1,exports)}
    }
    @Test fun runtimeFailureRestoresInstallerButtonAndDisplaysReason(){
        val report=mutableStateOf("""{"runtime_ready":true,"message":"Ready"}""")
        compose.setContent {MaterialTheme {SetupPanel(report.value,false,{},{})}}
        compose.onNodeWithText("Install runtime").assertDoesNotExist()
        compose.runOnIdle {report.value="""{"runtime_ready":false,"message":"Packet decoder missing"}"""}
        compose.onNodeWithText("Install runtime").assertIsEnabled()
        compose.onNodeWithText("Packet decoder missing").assertIsDisplayed()
    }
    @Test fun completedCaptureBecomesExportableWithoutReplacingTheSavedRow(){
        val origin="bw_1000_000000000051"
        val radio=MutableStateFlow(RadioUiState(busy=true,sessionId=origin,operation="capture"))
        var artifactReady=false;var exports=0
        compose.setContent {MaterialTheme {LazyColumn {item {
            SessionCaptureExport("imported:$origin",{exports++},radio,{if(artifactReady)java.io.File("synthetic.pcap") else null})
        }}}}
        compose.onNodeWithText("Export private PCAP").assertDoesNotExist()
        compose.runOnIdle {artifactReady=true}
        compose.onNodeWithText("Export private PCAP").assertDoesNotExist()
        compose.runOnIdle {radio.value=radio.value.copy(busy=false)}
        compose.onNodeWithText("Export private PCAP").performClick()
        compose.runOnIdle {assertEquals(1,exports)}
    }
}
