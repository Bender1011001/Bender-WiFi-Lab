package dev.sir.benderwifi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sir.benderwifi.wps.WpsScreen
import dev.sir.benderwifi.wps.WpsProgress
import dev.sir.benderwifi.wps.WpsProgressPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WpsUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun missingExchangeCannotRunAndCollectionLimitIsVisible(){
        var imports=0;var runs=0
        compose.setContent {MaterialTheme {WpsScreen("Synthetic network",false,false,"Import an exchange",{}, {imports++},{runs++},{})}}
        compose.onNodeWithText("Run Pixie Dust").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Import WPS exchange").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,imports);assertEquals(0,runs)}
        compose.onNodeWithText("Collect and run Pixie Dust").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check 3 common PINs").performScrollTo().assertIsNotEnabled()
    }
    @Test fun commonPinActionIsExplicitForSelectedNetwork(){
        var starts=0
        compose.setContent {MaterialTheme {WpsScreen("Synthetic network",false,false,"Ready",{},{},{},{},liveAvailable=true,onCommon={starts++})}}
        compose.onNodeWithText("Check 3 common PINs").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,starts)}
    }
    @Test fun activeSolverOffersCancelWithoutAnotherStart(){
        var cancellations=0
        compose.setContent {MaterialTheme {WpsScreen("Synthetic network",true,false,"Searching",{},{},{},{cancellations++})}}
        compose.onNodeWithText("Run Pixie Dust").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,cancellations)}
    }
    @Test fun stalledWorkerShowsElapsedTimeStageAndCleanupAction(){
        val progress=WpsProgress("synthetic",1000).event("collecting_exchange","Waiting for AP",2000,45_000).heartbeat(3000,"Radio worker")
        compose.setContent {MaterialTheme {WpsProgressPanel(progress,nowOverride=20_000)}}
        compose.onNodeWithText("Collect WPS exchange").assertIsDisplayed()
        compose.onNodeWithText("Elapsed 0:19 · stage 0:18").assertIsDisplayed()
        compose.onNodeWithText("Stage limit in 0:27").assertIsDisplayed()
        compose.onNodeWithText("No worker update for 17s. It may be stalled; Cancel requests cleanup.").assertIsDisplayed()
    }
    @Test fun aliveWorkerDoesNotClaimAnApResponse(){
        val progress=WpsProgress("synthetic",1000).event("collecting_exchange","Waiting for AP",2000,45_000).heartbeat(19_000,"Radio worker")
        compose.setContent {MaterialTheme {WpsProgressPanel(progress,nowOverride=20_000)}}
        compose.onNodeWithText("Radio worker: alive · checked 1s ago").assertIsDisplayed()
        compose.onNodeWithText("Last stage / protocol event 18s ago").assertIsDisplayed()
        compose.onNodeWithText("A heartbeat confirms worker liveness, not an AP response or a recovered PIN.").assertIsDisplayed()
    }
    @Test fun progressKeepsCancelVisibleAboveTheTimeline(){
        val now=android.os.SystemClock.elapsedRealtime()
        val progress=WpsProgress("synthetic",now-22000)
            .event("collecting_exchange","WPS worker answered its control check",now-21000,45000)
            .event("collecting_exchange","Fresh AP record found; WPS is advertised",now-20000)
            .event("collecting_exchange","WPS request accepted locally; waiting for the AP",now-18000)
            .heartbeat(now-1000,"Radio worker")
        var cancellations=0
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFC6F35B),background=Color(0xFF111512),surface=Color(0xFF1D241F))){
            WpsScreen("Synthetic lab AP · 5 GHz",true,false,"",{},{},{},{cancellations++},progress=progress)
        }}
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Collect WPS exchange").assertIsDisplayed()
        compose.waitForIdle()
        if(android.os.Build.HARDWARE in setOf("ranchu","goldfish")){
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val file=java.io.File(instrumentation.targetContext.filesDir,"validation/wps-progress.png")
            file.parentFile!!.mkdirs()
            // Capture the rendered Compose root after PixelCopy's draw fence;
            // a raw display screenshot can race the test activity's first frame.
            compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap->
                file.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
                bitmap.recycle()
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {assertEquals(1,cancellations)}
    }
}
