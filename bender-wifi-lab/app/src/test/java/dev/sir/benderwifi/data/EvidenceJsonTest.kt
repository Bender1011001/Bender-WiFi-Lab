package dev.sir.benderwifi.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EvidenceJsonTest {
    @Test fun wpsSessionKeysAndHashesAreRedactedButOutcomeBooleansSurvive(){
        val input=JSONObject().put("transcript_verified",true).put("enrollment_performed",false)
        for(key in listOf("AuthKey","E-Hash1","e_hash2","ES1","ES2","PSK1","PSK2","KWKey","EMSK","KDK"))input.put(key,"private-value-$key")
        val sanitized=EvidenceJson.parseSanitized(input.toString())
        assertFalse(sanitized.toString().contains("private-value"))
        assertTrue(sanitized.getBoolean("transcript_verified"))
        assertFalse(sanitized.getBoolean("enrollment_performed"))
    }
    @Test fun nestedSecretsAndNamingVariantsAreRemovedBeforePersistence(){
        val inner=JSONObject().put("network_key","NETWORK SECRET").put("WPS-PIN","12345670").put("pin_value","PIN SECRET").put("anonce_hex","NONCE SECRET").put("mic_hex","MIC SECRET").put("key-data","KEYDATA SECRET")
        val text=JSONObject().put("nested",inner.toString()).put("raw",inner).toString()
        val sanitized=EvidenceJson.parseSanitized(text).toString()
        listOf("NETWORK SECRET","12345670","PIN SECRET","NONCE SECRET","MIC SECRET","KEYDATA SECRET").forEach {assertFalse(sanitized.contains(it))}
        val shared=ShareRedactor().clean(JSONObject(text)).toString()
        listOf("NETWORK SECRET","12345670","PIN SECRET","NONCE SECRET","MIC SECRET","KEYDATA SECRET").forEach {assertFalse(shared.contains(it))}
    }
    @Test fun excessiveNestingAndNodeCountAreRejectedBeforeParsing(){
        val deep="{\"data\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}"
        assertTrue(runCatching {EvidenceJson.parseSanitized(deep)}.exceptionOrNull() is IllegalArgumentException)
        val wide="{\"data\":["+List(100_001){"0"}.joinToString(",")+"]}"
        assertTrue(runCatching {EvidenceJson.parseSanitized(wide)}.exceptionOrNull() is IllegalArgumentException)
        // org.json accepts single quotes; reject them so their double-quote content cannot hide real nesting from preflight.
        val relaxed="{'x':'\"','deep':"+"[".repeat(1000)+"0"+"]".repeat(1000)+",'z':'\"'}"
        assertTrue(runCatching {EvidenceJson.parseSanitized(relaxed)}.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun stringEscapesDoNotCountAsStructuralNesting(){
        val original=JSONObject().put("text","[".repeat(1000)+"\\\"{"+"}".repeat(1000)).toString()
        // A JSON-looking string is validated separately and rejected/omitted, never recursively parsed unchecked.
        assertTrue(runCatching {EvidenceJson.parseSanitized(original)}.exceptionOrNull() is IllegalArgumentException)
        EvidenceJson.preflight(original)
    }
}
