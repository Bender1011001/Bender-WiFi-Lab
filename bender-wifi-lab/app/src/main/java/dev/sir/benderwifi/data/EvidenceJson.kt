package dev.sir.benderwifi.data

import org.json.JSONArray
import org.json.JSONObject

/** Validate size/structure before Android's recursive JSON parser sees untrusted input. */
internal object EvidenceJson {
    private const val MAX_DEPTH=48
    private const val MAX_NODES=100_000
    internal fun preflight(text:String,baseDepth:Int=0) {
        require(text.length<=10_000_000){"Evidence JSON exceeds 10 MB"}
        var depth=baseDepth;var tokens=0;var quoted=false;var escaped=false
        for(char in text) {
            if(quoted){if(escaped)escaped=false else if(char=='\\')escaped=true else if(char=='\"')quoted=false;continue}
            when(char){
                '\'', '/', '#'->throw IllegalArgumentException("Evidence must use standard JSON strings without comments")
                '\"'->{quoted=true;tokens++}
                '{','['->{depth++;tokens++;require(depth<=MAX_DEPTH){"Evidence JSON nesting exceeds $MAX_DEPTH"}}
                '}',']'->{depth--;require(depth>=baseDepth){"Unbalanced evidence JSON"}}
                ',',':'->tokens++
            }
            require(tokens<=MAX_NODES){"Evidence JSON node budget exceeded"}
        }
        require(!quoted&&depth==baseDepth){"Incomplete evidence JSON"}
    }
    fun parseSanitized(text:String):JSONObject {
        preflight(text)
        return Walker().clean(JSONObject(text),0) as JSONObject
    }
    private class Walker {
        var nodes=0;var nestedChars=0
        fun clean(value:Any?,depth:Int):Any? {
            require(depth<=MAX_DEPTH&&++nodes<=MAX_NODES){"Evidence JSON structure budget exceeded"}
            return when(value){
                is JSONObject->JSONObject().also {out->value.keys().forEach {key->out.put(key,if(SecretFields.matches(key))"[REDACTED]" else clean(value.get(key),depth+1))}}
                is JSONArray->JSONArray().also {out->for(i in 0 until value.length())out.put(clean(value.get(i),depth+1))}
                is String->{
                    val text=value.trim()
                    if(text.startsWith("{")||text.startsWith("[")) {
                        nestedChars+=text.length;require(nestedChars<=10_000_000){"Embedded evidence JSON budget exceeded"}
                        preflight(text,depth)
                        val parsed=runCatching<Any>{if(text.startsWith("{"))JSONObject(text) else JSONArray(text)}.getOrNull()
                        if(parsed==null)"[Malformed embedded evidence omitted]" else clean(parsed,depth+1)
                    }else value
                }
                else->value
            }
        }
    }
}

/** Names are normalized so network-key, network_key and networkKey share a policy. */
internal object SecretFields {
    private val names=setOf("key","keyhex","networkkey","networkkeyhex","pin","wpspin","devicepin","registrarpin","enrolleepin","wscpin","psk","pmk","ptk","gtk","credential","credentials","keymaterial","keydata","encryptedkeydata","nonce","anonce","snonce","enonce","rnonce","mic","eapolmic","keymic","framehex","packethex","packetbytes","rawpacket","rawframe")
    fun matches(key:String):Boolean {
        val normalized=key.lowercase().filter {it.isLetterOrDigit()}
        if(normalized in setOf("authkey","kdk","emsk","kwkey","es1","es2","ehash1","ehash2","psk1","psk2"))return true
        return normalized in names || listOf("password","passphrase","secret","networkkey","wpspin","nonce","keydata").any {normalized.contains(it)} || normalized.endsWith("pin") || normalized.endsWith("mic") || normalized.startsWith("pin") || normalized.startsWith("mic") || normalized.startsWith("eapolmic") || normalized.startsWith("keymic")
    }
}
