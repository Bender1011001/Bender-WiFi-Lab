package dev.sir.benderwifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Checks the actual activity entry/return; never requests permission or starts discovery. */
class BluetoothEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun bluetoothWorkspaceOpensAndReturnsToWifi() {
        compose.onNodeWithText("Bluetooth").performClick()
        compose.onNodeWithText("BLUETOOTH LAB · PREVIEW").assertIsDisplayed()
        saveBluetoothScreenshot("bluetooth-empty-emulator")
        compose.onNodeWithText("Research").performClick()
        compose.onNodeWithText("Research catalog").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi").performClick()
        compose.onNodeWithText("Scan. Choose a network or device. Take action.").assertIsDisplayed()
    }
}
