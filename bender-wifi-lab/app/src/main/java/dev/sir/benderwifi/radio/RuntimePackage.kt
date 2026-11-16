package dev.sir.benderwifi.radio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Only the APK's pinned runtime can be staged. No arbitrary URLs or shell input. */
internal object RuntimePackage {
    fun stage(context:Context,cancel:File):Pair<File,JSONObject> {
        val manifest=context.assets.open("runtime-image/manifest.json").bufferedReader().use {JSONObject(it.readText())}
        require(manifest.optInt("schema_version")==1&&manifest.optString("arch")=="arm64"){"Unsupported bundled runtime"}
        val expected=manifest.getString("sha256")
        require(expected.matches(Regex("[a-f0-9]{64}"))){"Invalid runtime manifest"}
        val size=manifest.getLong("archive_bytes")
        val unpacked=manifest.getLong("installed_bytes")
        require(size in 1..600_000_000L&&unpacked in 1..3_000_000_000L){"Runtime package exceeds supported limits"}
        val directory=File(context.filesDir,"runtime-package").apply{mkdirs()}
        // The app stages one copy; the root installer verifies a second private
        // copy before extraction. Budget both, plus filesystem overhead.
        require(directory.usableSpace>2*size+unpacked+128L*1024*1024){"Free at least 1 GB of storage before installing the runtime"}
        val target=File(directory,"rootfs.tar.gz")
        val temporary=File(directory,"rootfs.pending")
        try {
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            context.assets.open("runtime-image/rootfs.bundle").use {input->temporary.outputStream().use {output->
                val buffer=ByteArray(128*1024)
                while(true){
                    check(!cancel.exists()){"Setup cancelled before root installation"}
                    val length=input.read(buffer);if(length<0)break
                    count+=length;require(count<=size){"Bundled runtime is larger than its manifest"}
                    digest.update(buffer,0,length);output.write(buffer,0,length)
                }
                output.fd.sync()
            }}
            val actual=digest.digest().joinToString(""){"%02x".format(it)}
            require(count==size&&actual==expected){"Bundled runtime integrity check failed"}
            check(!cancel.exists()){"Setup cancelled before root installation"}
            check(temporary.renameTo(target)){"Could not stage the runtime"}
            return target to manifest
        } finally {temporary.delete()}
    }
}
