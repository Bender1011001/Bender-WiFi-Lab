package dev.sir.benderwifi.wps

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Private exchange material. Deliberately no data-class toString or persistence. */
class PixieExchange internal constructor(private val fields:Map<String,String>) {
    internal fun stdin() = keys.joinToString("\n", postfix="\n") { fields.getValue(it) }
    internal fun bytes(key:String) = fields.getValue(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun toString() = "PixieExchange(<private>)"
    companion object { internal val keys=listOf("pke","pkr","e_hash1","e_hash2","authkey","e_nonce") }
}

object PixieProtocol {
    const val MAX_INPUT=64*1024
    private val sizes=mapOf("pke" to 384,"pkr" to 384,"e_hash1" to 64,"e_hash2" to 64,"authkey" to 64,"e_nonce" to 32)
    private val labels=mapOf("PKE" to "pke","PKR" to "pkr","E-Hash1" to "e_hash1","E-Hash2" to "e_hash2","AuthKey" to "authkey","E-Nonce" to "e_nonce")
    private val line=Regex("^\\s*(?:\\[[+*P]\\]\\s*)?(PKE|PKR|E-Hash1|E-Hash2|AuthKey|E-Nonce)\\s*:\\s*(.*?)\\s*$",RegexOption.IGNORE_CASE)
    fun read(input:InputStream):String {
        val buffer=ByteArray(4096);val result=java.io.ByteArrayOutputStream()
        while(true){val count=input.read(buffer);if(count<0)break;require(result.size()+count<=MAX_INPUT){"Select one WPS exchange, at most 64 KiB."};result.write(buffer,0,count)}
        return result.toByteArray().toString(Charsets.UTF_8)
    }
    fun parse(text:String):PixieExchange {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_INPUT){"Select one WPS exchange, at most 64 KiB."}
        val fields=mutableMapOf<String,String>()
        fun add(key:String,value:String){
            val hex=value.replace(Regex("[:\\s]"),"").lowercase()
            require(hex.length==sizes.getValue(key)&&hex.all {it in "0123456789abcdef"}){"Invalid $key field length or encoding."}
            require(!fields.containsKey(key)){"Multiple $key fields: import one exchange, not a combined log."}
            fields[key]=hex
        }
        if(text.trimStart().startsWith("{")) {
            val json=try{JSONObject(text)}catch(_:Exception){throw IllegalArgumentException("Invalid WPS JSON.")}
            require(json.keys().asSequence().toSet()==sizes.keys){"WPS JSON must contain exactly pke, pkr, e_hash1, e_hash2, authkey and e_nonce."}
            // Reject duplicate JSON members too; JSONObject normally keeps the last.
            for(key in sizes.keys){
                require(Regex("\""+key+"\"\\s*:").findAll(text).count()==1){"Duplicate WPS field."}
                require(json.opt(key) is String){"WPS fields must be hex strings."}
                add(key,json.getString(key))
            }
        } else text.lineSequence().forEach { row ->line.matchEntire(row)?.let {m->
            val key=labels.entries.first {it.key.equals(m.groupValues[1],true)}.value
            add(key,m.groupValues[2])
        }}
        require(fields.keys==sizes.keys){"Missing WPS exchange fields. A normal WPA handshake or beacon is not enough."}
        return PixieExchange(fields)
    }
    private fun hmac(key:ByteArray,data:ByteArray):ByteArray=Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(key,"HmacSHA256"));doFinal(data)}
    /** Independent WPS E-Hash1/E-Hash2 verification; no trust in solver exit code. */
    fun verifiedPin(exchange:PixieExchange,output:String):String? {
        require(output.length<=MAX_INPUT){"Solver output exceeded its bound."}
        val matches=Regex("(?m)^\\s*\\[\\+\\] WPS pin:  (\\d{4}|\\d{8}|<empty>)\\s*$").findAll(output).toList()
        if(matches.isEmpty())return null
        require(matches.size==1){"Ambiguous solver result."}
        val pin=matches.single().groupValues[1].let {if(it=="<empty>")"" else it}
        fun nonce(name:String):ByteArray {
            val found=Regex("(?m)^\\s*\\[\\*\\] $name:\\s*([0-9a-fA-F:]{32,47})\\s*$").findAll(output).toList()
            require(found.size==1){"Solver verification material is missing."}
            val hex=found.single().groupValues[1].replace(":","")
            require(hex.length==32){"Invalid solver nonce."}
            return hex.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        }
        val key=exchange.bytes("authkey")
        val half=pin.length/2
        for((index,part) in listOf(pin.take(half),pin.drop(half)).withIndex()) {
            val psk=hmac(key,part.toByteArray(Charsets.US_ASCII)).copyOf(16)
            val hash=hmac(key,nonce("ES${index+1}")+psk+exchange.bytes("pke")+exchange.bytes("pkr"))
            require(MessageDigest.isEqual(hash,exchange.bytes("e_hash${index+1}"))){"Solver candidate failed independent WPS verification."}
        }
        return pin
    }
}
