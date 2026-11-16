package dev.sir.benderwifi

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.LabRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Disposable emulator previews only. No radio, connectivity or credential action is invoked. */
class PreviewScreensTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun captureEmptyRadarAndWorkshopOnDebugEmulatorOnly() {
        val fingerprint=Build.FINGERPRINT.lowercase()
        val model=Build.MODEL.lowercase()
        val emulatorFingerprint=fingerprint.startsWith("generic")||fingerprint.contains("sdk_gphone")||fingerprint.contains("emulator")
        val emulatorModel=model.contains("sdk_gphone")||model.contains("emulator")||model.contains("android sdk built for")
        assumeTrue("Preview captures require a debug emulator build",BuildConfig.DEBUG&&emulatorFingerprint&&emulatorModel&&Build.HARDWARE.lowercase() in setOf("ranchu","goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val repo=LabRepository.get(context)
        val empty=runBlocking {
            repo.networks.first().isEmpty()&&repo.clients.first().isEmpty()&&repo.credentials.first().isEmpty()&&repo.sessions.first().isEmpty()&&repo.experiments.first().isEmpty()
        }
        assumeTrue("Preview captures require an empty disposable inventory and vault",empty)
        val directory=File(context.filesDir,"validation")
        check(directory.isDirectory||directory.mkdirs())
        try {
            compose.activityRule.scenario.onActivity {activity->
                assertEquals("Screenshots are enabled",0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("network-list").performScrollToNode(hasText("No networks yet. Scan or import a capture from Tools."))
            compose.onNodeWithText("No networks yet. Scan or import a capture from Tools.").assertIsDisplayed()
            compose.onNodeWithTag("network-list").performScrollToIndex(0)
            compose.onNodeWithText("Scan networks & devices").assertIsDisplayed()
            capture(File(directory,"radar.png"))
            compose.onAllNodesWithText("Tools").onLast().performClick()

            compose.onNodeWithTag("workshop-list").performScrollToNode(hasText("Automatic privacy setup"))
            compose.onNodeWithText("Automatic privacy setup").assertIsDisplayed()
            compose.onNodeWithText("Strict radio isolation").assertExists()
            capture(File(directory,"workshop.png"))
        } finally {
            compose.activityRule.scenario.onActivity {assertEquals(0,it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
        }
        compose.activityRule.scenario.onActivity {activity->
            assertEquals("Screenshot policy stays unchanged",0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun capture(file:File) {
        compose.waitForIdle()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {"Emulator screenshot unavailable"}
        try {
            assertTrue(bitmap.width>0&&bitmap.height>0)
            file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            assertTrue("Screenshot must be written",file.length()>0)
        } finally {bitmap.recycle()}
    }
}
