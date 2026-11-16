package dev.sir.benderwifi.wps

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import dev.sir.benderwifi.data.LabRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Private memory-only transport: the root collector never writes keys to its logs. */
class LiveWpsBridge(private val context:Context,private val session:String,private val bssid:String,private val strategy:String="pixie"):AutoCloseable {
    val name="bender-wps-${UUID.randomUUID().toString().replace("-", "")}"
    private val server=LocalServerSocket(name)
    @Volatile private var peer:LocalSocket?=null
    companion object {
        private val mutable=MutableStateFlow("Ready to collect a live WPS exchange.")
        val status:StateFlow<String> = mutable
        private val phases=mapOf("preparing_radio" to "Preparing a private managed interface…",
            "collecting_exchange" to "Collecting a WPS exchange · up to 45 seconds…",
            "solving" to "Running Pixie Dust locally · up to 120 seconds…",
            "requesting_credentials" to "PIN verified and saved · requesting the AP’s settings…")
    }
    suspend fun run()=withContext(Dispatchers.IO) {
        try {
            server.accept().use { socket ->
                peer=socket
                require(socket.peerCredentials.uid==0){"Unexpected collector"}
                socket.soTimeout=240_000
                val input=DataInputStream(socket.inputStream)
                val output=DataOutputStream(socket.outputStream)
                fun read():JSONObject {
                    val length=input.readInt()
                    require(length in 1..16_384){"Invalid message size"}
                    val bytes=ByteArray(length);input.readFully(bytes)
                    return JSONObject(bytes.toString(Charsets.UTF_8))
                }
                fun send(value:JSONObject){
                    val bytes=value.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size<=16_384)
                    output.writeInt(bytes.size);output.write(bytes);output.flush()
                }
                val hello=read()
                require(hello.optString("type")=="hello" && hello.optString("session")==session && hello.optString("bssid")==bssid)
                send(JSONObject().put("type","ready"))
                var pinSaved=false
                var exchangeSeen=false
                while(true){
                    ensureActive()
                    val message=read()
                    when(message.getString("type")) {
                        "phase" -> {
                            val phase=message.optString("phase")
                            mutable.value=phases[phase]?:Regex("trying_pin_(\\d+)_of_(\\d+)").matchEntire(phase)?.let {
                                "Checking PIN ${it.groupValues[1]} of ${it.groupValues[2]}…"
                            }?:"WPS operation in progress…"
                        }
                        "exchange" -> {
                            require(!exchangeSeen);exchangeSeen=true
                            val exchange=PixieProtocol.parse(message.getJSONObject("fields").toString())
                            val pin=PixieRunner.solve(File(context.applicationInfo.nativeLibraryDir,"libbender_pixie.so"),exchange)
                            if(pin.isNullOrEmpty()) {
                                mutable.value=if(pin==null)"No PIN found in this bounded search." else "Empty PIN matched the transcript; automatic enrollment is unsupported."
                                send(JSONObject().put("type","not_found"))
                                return@withContext
                            }
                            withContext(NonCancellable){
                                LabRepository.get(context).saveLiveWpsSecret(pin,bssid,session,"pin",JSONObject().put("both_hashes_verified",true))
                            }
                            pinSaved=true
                            send(JSONObject().put("type","pin").put("pin",pin))
                        }
                        "credential" -> {
                            require(pinSaved)
                            val fields=message.getJSONObject("fields")
                            val secret=LiveWpsProtocol.password(fields)
                            withContext(NonCancellable){
                                LabRepository.get(context).saveLiveWpsSecret(secret,bssid,session,"password",
                                    JSONObject().put("ssid_hex",fields.getString("ssid_hex")).put("auth_type",fields.getInt("auth_type"))
                                        .put("ap_settings_received",true).put("association_verified",false))
                            }
                            send(JSONObject().put("type","saved"))
                            mutable.value="PIN and AP-provided password encrypted in Vault. Waiting for radio cleanup…"
                            return@withContext
                        }
                        "trial_credential" -> {
                            require(strategy=="common_pins")
                            val pin=message.getString("pin")
                            require(pin in setOf("00000000","12345670","11111115"))
                            val fields=message.getJSONObject("fields")
                            val secret=LiveWpsProtocol.password(fields)
                            withContext(NonCancellable){
                                val repository=LabRepository.get(context)
                                repository.saveLiveWpsSecret(pin,bssid,session,"pin",
                                    JSONObject().put("source","common_pin_trial").put("ap_settings_received",true))
                                repository.saveLiveWpsSecret(secret,bssid,session,"password",
                                    JSONObject().put("source","common_pin_trial").put("ssid_hex",fields.getString("ssid_hex"))
                                        .put("auth_type",fields.getInt("auth_type")).put("ap_settings_received",true)
                                        .put("association_verified",false))
                            }
                            send(JSONObject().put("type","saved"))
                            mutable.value="AP supplied settings for a trial PIN. PIN and password encrypted in Vault."
                            return@withContext
                        }
                        else -> error("Unexpected message")
                    }
                }
            }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){mutable.value="Live WPS did not complete. Any PIN already verified remains in Vault; inspect the session result."}
        finally{close()}
    }
    override fun close(){runCatching {peer?.close()};runCatching {server.close()}}
}

object LiveWpsProtocol {
    /** Preserve SSID bytes; accept only supported WPA-PSK credentials. */
    fun password(fields:JSONObject):String {
        fun hex(name:String,min:Int,max:Int):ByteArray {
            val value=fields.getString(name)
            require(value.length%2==0 && value.length/2 in min..max && value.all {it in "0123456789abcdef"})
            return value.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        }
        hex("ssid_hex",0,32);hex("mac_hex",6,6)
        require(fields.getInt("auth_type") and (0x02 or 0x20) != 0)
        require(fields.getInt("encr_type") and (0x04 or 0x08) != 0)
        val key=hex("key_hex",8,64)
        require(key.all {(it.toInt() and 255) in 32..126})
        val value=key.toString(Charsets.US_ASCII)
        require(value.length!=64 || value.all {it.lowercaseChar() in "0123456789abcdef"})
        return value
    }
}
