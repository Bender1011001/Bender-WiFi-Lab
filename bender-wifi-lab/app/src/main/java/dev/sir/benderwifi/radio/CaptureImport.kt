package dev.sir.benderwifi.radio

import java.io.InputStream
import java.io.OutputStream

/** Copy under the controller's import reservation; never consume a cancelled pick. */
internal fun copyCapture(input:InputStream,output:OutputStream,cancelled:()->Boolean) {
    val buffer=ByteArray(65536);var total=0L
    while(true){
        check(!cancelled()){"Capture import cancelled"}
        val length=input.read(buffer)
        if(length<0)break
        check(!cancelled()){"Capture import cancelled"}
        total+=length
        require(total<=256L*1024*1024){"Capture exceeds 256 MiB"}
        output.write(buffer,0,length)
    }
}
