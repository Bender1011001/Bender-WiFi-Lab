package dev.sir.benderwifi

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Render evidence from tests using only synthetic data or the empty emulator UI. */
internal fun saveBluetoothScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val context=instrumentation.targetContext
    val directory=context.getExternalFilesDir(null)?:context.filesDir
    File(directory, "$name.png").outputStream().use {
        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    screenshot.recycle()
}
