package dev.sir.benderwifi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sir.benderwifi.wps.WpsScreen
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
}
