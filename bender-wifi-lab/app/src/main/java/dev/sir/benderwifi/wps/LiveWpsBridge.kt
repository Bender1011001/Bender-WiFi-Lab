package dev.sir.benderwifi.wps

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import dev.sir.benderwifi.data.LabRepository
import kotlinx.coroutines.*
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
                            require(phase in WpsMessages.stages || Regex("trying_pin_[1-3]_of_3").matches(phase))
                            WpsProgressStore.event(session,phase,WpsMessages.title(phase))
                        }
                        "heartbeat" -> WpsProgressStore.heartbeat(session,"Radio worker")
                        "activity" -> {
                            val remaining=if(message.has("remaining_ms"))message.getLong("remaining_ms").also {require(it in 0..45_000)} else null
                            WpsProgressStore.detail(session,message.getString("code"),remaining)
                        }
                        "failure" -> {
                            val text=WpsMessages.failures[message.optString("code")]?:WpsMessages.failures.getValue("worker_error")
                            val stage=WpsProgressStore.state.value?.takeIf {it.session==session}?.stage?:"setup"
                            WpsProgressStore.event(session,stage,text)
                        }
                        "exchange" -> {
                            require(!exchangeSeen);exchangeSeen=true
                            val exchange=PixieProtocol.parse(message.getJSONObject("fields").toString())
                            WpsProgressStore.event(session,"solving","Searching the collected exchange locally; no AP traffic during this stage",120_000)
                            val pin=try {
                                PixieRunner.solve(File(context.applicationInfo.nativeLibraryDir,"libbender_pixie.so"),exchange,
                                    onHeartbeat={WpsProgressStore.heartbeat(session,"Local solver process")})
                            }catch(e:TimeoutCancellationException){WpsProgressStore.detail(session,"solver_timeout");throw e}
                            if(pin.isNullOrEmpty()) {
                                WpsProgressStore.detail(session,"not_found")
                                send(JSONObject().put("type","not_found"))
                                return@withContext
                            }
                            withContext(NonCancellable){
                                LabRepository.get(context).saveLiveWpsSecret(pin,bssid,session,"pin",JSONObject().put("both_hashes_verified",true))
                            }
                            pinSaved=true
                            WpsProgressStore.detail(session,"pin_verified")
                            send(JSONObject().put("type","pin").put("pin",pin))
                        }
                        "credential" -> {
                            require(pinSaved)
                            val fields=message.getJSONObject("fields")
                            val secret=LiveWpsProtocol.password(fields)
                            WpsProgressStore.event(session,"saving","Encrypting authenticated AP settings in Vault")
                            withContext(NonCancellable){
                                LabRepository.get(context).saveLiveWpsSecret(secret,bssid,session,"password",
                                    JSONObject().put("ssid_hex",fields.getString("ssid_hex")).put("auth_type",fields.getInt("auth_type"))
                                        .put("ap_settings_received",true).put("association_verified",false))
                            }
                            send(JSONObject().put("type","saved"))
                            WpsProgressStore.detail(session,"credential_saved")
                            return@withContext
                        }
                        "trial_credential" -> {
                            require(strategy=="common_pins")
                            val pin=message.getString("pin")
                            require(pin in setOf("00000000","12345670","11111115"))
                            val fields=message.getJSONObject("fields")
                            val secret=LiveWpsProtocol.password(fields)
                            WpsProgressStore.event(session,"saving","Encrypting the verified PIN and AP settings in Vault")
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
                            WpsProgressStore.detail(session,"credential_saved")
                            return@withContext
                        }
                        else -> error("Unexpected message")
                    }
                }
            }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){WpsProgressStore.detail(session,"bridge_closed")}
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
