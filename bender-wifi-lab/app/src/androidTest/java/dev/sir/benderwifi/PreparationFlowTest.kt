package dev.sir.benderwifi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sir.benderwifi.ui.IdentityPanel
import dev.sir.benderwifi.ui.identityPreflight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Callback-only UI tests: no controller, root process or connectivity change is invoked. */
class PreparationFlowTest {
    @get:Rule val compose=createComposeRule()
    @Test fun automaticSetupHasOptionalStrictModeAndReadOnlyDetails(){
        var preparations=0
        var checks=0
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
            IdentityPanel(identityPreflight(null),true,false,{},{checks++},{},onStrict={preparations++})
        }}}
        compose.runOnIdle {assertEquals(0,preparations);assertEquals(0,checks)}
        compose.onNodeWithText("Automatic privacy setup").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle {assertEquals(1,preparations);assertEquals(0,checks)}
        compose.onNodeWithText("What changes / details").performScrollTo().performClick()
        compose.onNodeWithText("Last checked: never / unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check again").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,preparations);assertEquals(1,checks)}
        compose.onNodeWithText("Privacy / identity preflight").performScrollTo().assertIsDisplayed()
    }
    @Test fun pendingChangesOfferExplicitRestorationAndDisableAnotherPreparation(){
        var restorations=0
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
            IdentityPanel(identityPreflight(null),true,true,{restorations++},{},{})
        }}}
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.runOnIdle {assertEquals(0,restorations)}
        compose.onNodeWithText("Restore normal connectivity").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,restorations)}
    }
}
