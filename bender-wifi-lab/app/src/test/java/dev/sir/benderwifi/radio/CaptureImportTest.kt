package dev.sir.benderwifi.radio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CaptureImportTest {
    @Test fun cancelledSelectionWritesNoBytes(){
        val output=ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java){copyCapture(ByteArrayInputStream(byteArrayOf(1,2,3)),output){true}}
        assertEquals(0,output.size())
    }
    @Test fun truncatedAndMultiBufferInputsPreserveExactBytes(){
        val input=ByteArray(131079){(it%251).toByte()};val output=ByteArrayOutputStream()
        copyCapture(ByteArrayInputStream(input),output){false}
        assertArrayEquals(input,output.toByteArray())
    }
}
