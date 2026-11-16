package dev.sir.benderwifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sir.benderwifi.ui.RecoveryStatusBanner
import dev.sir.benderwifi.ui.integritySummary
import dev.sir.benderwifi.radio.RadioUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecoveryBannerTest {
    @get:Rule val compose=createComposeRule()
    @Test fun freshStateShowsActionableUncheckedRecoveryWithoutStartingAnything() {
        val fresh=RadioUiState()
        assertFalse(fresh.recoveryChecked)
        var checks=0
        compose.setContent {if(!fresh.recoveryChecked)RecoveryStatusBanner(true){checks++}}
        compose.onNodeWithText("Recovery state unchecked").assertIsDisplayed()
        assertEquals(0,checks)
        compose.onNodeWithText("Check recovery state").performClick()
        assertEquals(1,checks)
    }
    @Test fun integrityCountsDescribeUnknownMetadataWithoutCallingItValid() {
        val label=integritySummary("""{"frame_integrity":{"failed":2,"verified":1,"not_flagged":3,"unknown":4}}""")
        assertTrue(label.contains("2 frames excluded"));assertTrue(label.contains("1 checksum verified"));assertTrue(label.contains("7 unverified"))
    }
}
