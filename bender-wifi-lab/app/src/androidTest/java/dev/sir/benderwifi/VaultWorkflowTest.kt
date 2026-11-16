package dev.sir.benderwifi

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import dev.sir.benderwifi.data.*
import dev.sir.benderwifi.ui.LabApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VaultWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun saveRevealCopyAndBackgroundHideNeedNoExtraPrompt() {
        val context=compose.activity
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val repo=LabRepository(db,SecretVault(context))
        try {
            compose.activityRule.scenario.onActivity {it.setContent {LabApp(repo)}}
            assertEquals(0,context.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            compose.onNodeWithText("Vault").performClick()
            compose.onNodeWithText("Add credential").performClick()
            compose.onNodeWithText("Label / network name").performTextInput("SYNTHETIC UI ENTRY")
            compose.onNodeWithText("Password / secret").performTextInput("SYNTHETIC UI SECRET")
            compose.onNodeWithText("Save encrypted").performClick()
            compose.waitUntil(10_000){compose.onAllNodesWithText("Credential encrypted and saved.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Reveal").performClick()
            compose.waitUntil(10_000){compose.onAllNodesWithText("SYNTHETIC UI SECRET").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Copy").performClick()
            compose.runOnIdle {
                val clip=context.getSystemService(ClipboardManager::class.java).primaryClip!!
                assertEquals("SYNTHETIC UI SECRET",clip.getItemAt(0).text.toString())
                assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            }
            val record=runBlocking {repo.credentials.first().single()}
            assertTrue(record.wrappedKey.startsWith("v2:"))
            assertFalse(record.toString().contains("SYNTHETIC UI SECRET"))
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("SYNTHETIC UI SECRET").assertDoesNotExist()
        } finally {db.close()}
    }
}
