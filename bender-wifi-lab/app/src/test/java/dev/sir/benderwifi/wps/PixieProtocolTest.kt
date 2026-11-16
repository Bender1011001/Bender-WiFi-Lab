package dev.sir.benderwifi.wps

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PixieProtocolTest {
    private fun fixture()=javaClass.getResource("/pixie-synthetic.json")!!.readText()
    private val output="\n [*] ES1:      "+"00".repeat(16)+"\n [*] ES2:      "+"00".repeat(16)+"\n [+] WPS pin:  12345678\n"
    @Test fun independentHashVerificationAcceptsOnlyCorrectPin(){
        val exchange=PixieProtocol.parse(fixture())
        assertEquals("12345678",PixieProtocol.verifiedPin(exchange,output))
        assertThrows(IllegalArgumentException::class.java){PixieProtocol.verifiedPin(exchange,output.replace("12345678","12345670"))}
        assertFalse(exchange.toString().contains("authkey"))
    }
    @Test fun tamperingEitherHashOrNonceIsRejected(){
        for(key in listOf("e_hash1","e_hash2","authkey")){
            val changed=JSONObject(fixture()).put(key,"ff".repeat(32))
            assertThrows(IllegalArgumentException::class.java){PixieProtocol.verifiedPin(PixieProtocol.parse(changed.toString()),output)}
        }
        assertThrows(IllegalArgumentException::class.java){PixieProtocol.verifiedPin(PixieProtocol.parse(fixture()),output.replace("ES2:","Missing:"))}
    }
    @Test fun reaverFieldsAreParsedWithoutExecutingEmbeddedCommands(){
        val json=JSONObject(fixture());val labels=mapOf("PKE" to "pke","PKR" to "pkr","E-Hash1" to "e_hash1","E-Hash2" to "e_hash2","AuthKey" to "authkey","E-Nonce" to "e_nonce")
        val log=labels.entries.joinToString("\n"){"[P] ${it.key}: ${json.getString(it.value).chunked(2).joinToString(":")}"}+"\n ignored shell text"
        assertEquals("12345678",PixieProtocol.verifiedPin(PixieProtocol.parse(log),output))
        assertThrows(IllegalArgumentException::class.java){PixieProtocol.parse(log+"\n[P] PKE: "+json.getString("pke"))}
    }
    @Test fun incompleteDuplicateOversizedAndOptionInputsFail(){
        for(text in listOf("{}","{\"pke\":\"--help\"}",fixture().replaceFirst("{","{\"pke\":\"aa\","),"x".repeat(PixieProtocol.MAX_INPUT+1))){
            assertThrows(IllegalArgumentException::class.java){PixieProtocol.parse(text)}
        }
        assertThrows(IllegalArgumentException::class.java){PixieProtocol.read(ByteArray(PixieProtocol.MAX_INPUT+1).inputStream())}
    }
    @Test fun absenceOfPinIsNotRecoveryAndAmbiguousOutputFails(){
        val exchange=PixieProtocol.parse(fixture())
        assertNull(PixieProtocol.verifiedPin(exchange,"WPS pin not found!"))
        assertThrows(IllegalArgumentException::class.java){PixieProtocol.verifiedPin(exchange,output+output)}
    }
}
