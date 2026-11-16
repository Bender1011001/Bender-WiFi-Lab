package dev.sir.benderwifi

import android.os.ParcelFileDescriptor
import android.text.InputType
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Inspect the platform IME contract, not just the field's masking semantics. */
class PasswordInputTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun vaultSecretUsesPasswordVariationWithoutAutocorrect(){
        compose.onAllNodesWithText("Vault").onLast().performClick()
        compose.onNodeWithText("Add credential").performClick()
        compose.onNodeWithText("Password / secret").performClick()
        var type:Int?=null
        compose.waitUntil(10_000){
            val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
            val dump=ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}
            val editor=dump.substringAfter("mCurrentEditorInfo:","").substringBefore("mShowRequested")
            if(editor.contains("packageName=dev.sir.benderwifi")) {
                type=Regex("inputType=0x([a-fA-F0-9]+)").find(editor)?.groupValues?.get(1)?.toInt(16)
            }
            type!=null && type!! and InputType.TYPE_MASK_VARIATION==InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertEquals(InputType.TYPE_CLASS_TEXT,type!! and InputType.TYPE_MASK_CLASS)
        assertEquals(0,type!! and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        // No password is entered, saved, exported or logged by this test.
        compose.onNodeWithText("Cancel").performClick()
    }
}
