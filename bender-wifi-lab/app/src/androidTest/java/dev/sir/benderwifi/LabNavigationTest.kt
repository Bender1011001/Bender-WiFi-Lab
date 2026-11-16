package dev.sir.benderwifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** No scan, probe, restore or lab operation is triggered by this navigation test. */
class LabNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun networkFirstNavigationKeepsSetupAndSavedEvidenceAccessible(){
        compose.onNodeWithText("Scan. Choose a network or device. Take action.").assertIsDisplayed()
        compose.onNodeWithText("Scan networks & devices").assertIsDisplayed()
        compose.onAllNodesWithText("Tools").onLast().performClick()
        compose.onNodeWithTag("workshop-list").performScrollToNode(hasText("Automatic privacy setup"))
        compose.onNodeWithText("Automatic privacy setup").assertIsDisplayed()
        compose.onNodeWithText("Strict radio isolation").assertExists()
        compose.onAllNodesWithText("Vault").onLast().performClick()
        compose.onNodeWithText("Add credential").assertExists()
        compose.onAllNodesWithText("Evidence").onLast().performClick()
        compose.onNodeWithText("Export redacted").assertExists()
    }
}
