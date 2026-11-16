package dev.sir.benderwifi

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.radio.RuntimePackage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real APK assets and Android filesystem; no root or install operation. */
class RuntimePackageTest {
    private fun fixture(test:(ContextWrapper,File)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"runtime-staging-test-${UUID.randomUUID()}").apply{check(mkdirs())}
        val isolated=object:ContextWrapper(context){override fun getFilesDir()=directory}
        try {test(isolated,directory)} finally {directory.deleteRecursively()}
    }
    @Test fun bundledImageStagesWithItsPinnedSizeOnAndroid()=fixture {context,directory->
        val (image,metadata)=RuntimePackage.stage(context,File(directory,"cancel"))
        assertEquals(metadata.getLong("archive_bytes"),image.length())
        assertEquals("rootfs.tar.gz",image.name)
        assertTrue(image.isFile)
        assertFalse(File(image.parentFile,"rootfs.pending").exists())
    }
    @Test fun cancellationPreservesExistingImageAndRemovesPartialCopy()=fixture {context,directory->
        val target=File(directory,"runtime-package/rootfs.tar.gz")
        check(target.parentFile!!.mkdirs());target.writeText("previous staged image")
        val cancel=File(directory,"cancel").apply{writeText("cancel")}
        val failure=runCatching {RuntimePackage.stage(context,cancel)}.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("previous staged image",target.readText())
        assertFalse(File(target.parentFile,"rootfs.pending").exists())
    }
    @Test fun packagedShellScriptsParseWithTheActualAndroidShell()=fixture {context,directory->
        val scripts=context.assets.list("runtime").orEmpty().filter {it.endsWith(".sh")}
        assertTrue("Runtime shell assets are missing",scripts.isNotEmpty())
        scripts.forEach {name->
            val bytes=context.assets.open("runtime/$name").use {it.readBytes()}
            assertFalse("$name contains CR line endings",bytes.any {it==13.toByte()})
            val script=File(directory,name).apply {writeBytes(bytes)}
            // Parse only: no root, settings changes or radio operation.
            val process=ProcessBuilder("/system/bin/sh","-n",script.absolutePath).redirectErrorStream(true).start()
            val output=process.inputStream.bufferedReader().use {it.readText()}
            assertEquals("Android rejected $name: $output",0,process.waitFor())
        }
    }
}
