package dev.sir.benderwifi.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LabRepository internal constructor(private val db:LabDatabase,private val vault:SecretVault=SecretVault()) {
    private constructor(context:Context):this(Room.databaseBuilder(context.applicationContext,LabDatabase::class.java,"bender-lab.db").addMigrations(LabDatabase.MIGRATION_1_2).build(),SecretVault(context.applicationContext))
    private val dao=db.dao()
    val networks=dao.networks(); val clients=dao.clients(); val experiments=dao.experiments(); val credentials=dao.credentials(); val sessions=dao.sessions()
    suspend fun getNetwork(id:String)=dao.network(id)
    suspend fun getClient(id:String)=dao.client(id)
    fun networkExperiments(id:String)=dao.networkExperiments(id)
    fun networkSessions(id:String)=dao.networkSessions(id)
    fun networkHandshakes(id:String)=dao.networkHandshakes(id)
    fun networkNote(id:String)=dao.networkNote(id)
    suspend fun saveNote(id:String,text:String){
        require(NetworkWork.networkId(id)==id && text.length<=10000){"Invalid network note"}
        dao.putNote(NetworkNoteEntity(id,text,System.currentTimeMillis()))
    }
    private suspend fun linkWork(session:String,network:String){
        NetworkWork.origins(session).forEach {dao.putTarget(WorkTargetEntity(it,network))}
    }
    suspend fun importObservations(json:String,sessionId:String)=withContext(Dispatchers.IO) {
        require(json.length<=10_000_000){"Observation file exceeds 10 MB"}
        val root=EvidenceJson.parseSanitized(json)
        require(root.optInt("schema_version")==1){"Expected observation schema version 1"}
        require(sessionId.isNotBlank()) {"Missing capture origin"}
        val now=System.currentTimeMillis()
        val cleaned=ObservationSchema().clean(root)
        db.withTransaction {
            // Session and observations commit together. Reconciliation can retry
            // after any process-death point without duplicating immutable evidence.
            if(dao.session(sessionId)!=null) return@withTransaction
            dao.putSession(SessionEntity(sessionId,if(sessionId.startsWith("imported:")) "historical import" else "runtime capture",now,cleaned.optJSONObject("source")?.toString()?:"{}",cleaned.optJSONArray("limits")?.toString()?:"[]"))
            val aps=cleaned.optJSONArray("networks")?:JSONArray()
            for(i in 0 until aps.length()) {
                val n=aps.getJSONObject(i); val id=n.getString("id");val bssid=n.getString("bssid")
                require(id=="ap:${bssid.lowercase()}" && mac.matches(bssid)){"Invalid AP identifier"}
                val previous=dao.network(id)
                val current=NetworkEntity(id,bssid,n.optString("ssid_display").ifEmpty{"Hidden / unknown SSID"},n.optString("security","unknown"),n.intOrNull("channel"),n.intOrNull("frequency_mhz"),n.intOrNull("signal_dbm"),n.optString("pmf_advertised","unknown"),n.timestamp("first_seen"),n.timestamp("last_seen"),sessionId,n.toString())
                val first=earliest(previous?.firstSeen,current.firstSeen)
                val prefer=previous==null || preferCurrent(previous.sessionId,previous.lastSeen,sessionId,current.lastSeen)
                val merged=IdentityHints.merge(previous?.rawJson,n,sessionId,prefer)
                dao.putNetwork((if(prefer)current else previous!!).copy(firstSeen=first,rawJson=merged.toString()))
                dao.putObservation(ObservationEntity(sessionId=sessionId,targetId=id,kind="network",rawJson=n.toString(),recordedAt=now))
                linkWork(sessionId,id)
            }
            val clients=cleaned.optJSONArray("clients")?:JSONArray()
            for(i in 0 until clients.length()) {
                val n=clients.getJSONObject(i); val id=n.getString("id");val address=n.getString("mac")
                require(id=="sta:${address.lowercase()}" && mac.matches(address)){"Invalid client identifier"}
                val previous=dao.client(id)
                val current=ClientEntity(id,address,n.nullableString("ap_id"),n.intOrNull("signal_dbm"),n.timestamp("first_seen"),n.timestamp("last_seen"),sessionId,n.toString())
                val first=earliest(previous?.firstSeen,current.firstSeen)
                val prefer=previous==null || preferCurrent(previous.sessionId,previous.lastSeen,sessionId,current.lastSeen)
                val merged=IdentityHints.merge(previous?.rawJson,n,sessionId,prefer)
                dao.putClient((if(prefer)current else previous!!).copy(firstSeen=first,rawJson=merged.toString()))
                dao.putObservation(ObservationEntity(sessionId=sessionId,targetId=id,kind="client",rawJson=n.toString(),recordedAt=now))
                NetworkWork.networkFromObservation("client",n)?.let {linkWork(sessionId,it)}
            }
            for(kind in listOf("handshakes","observations")) {
                val records=cleaned.optJSONArray(kind)?:JSONArray()
                for(i in 0 until records.length()) {
                    val value=records.optJSONObject(i)?:continue
                    val network=NetworkWork.networkFromObservation(kind,value)
                    dao.putObservation(ObservationEntity(sessionId=sessionId,targetId=network,kind=kind,rawJson=value.toString(),recordedAt=now))
                    if(network!=null)linkWork(sessionId,network)
                }
            }
        }
    }
    private suspend fun preferCurrent(previous:String,previousTime:Double,current:String,currentTime:Double):Boolean {
        val wasImport=previous.startsWith("imported:");val isImport=current.startsWith("imported:")
        if(wasImport!=isImport) return !isImport
        if(isImport) return currentTime>previousTime
        // Order live sessions by their acquisition identity, not the packet clock
        // or reconciliation order. Raw timestamps remain in immutable history.
        suspend fun order(id:String)=runtimeSession.matchEntire(id)?.groupValues?.get(1)?.toLongOrNull() ?: dao.session(id)?.importedAt ?: 0L
        return order(current)>=order(previous)
    }
    suspend fun recordExperiment(id:String,operation:String,state:String,result:String,request:JSONObject?=null,createdAt:Long?=null,preserveExistingEvidence:Boolean=false)=db.withTransaction {
        val previous=dao.experiment(id)
        val created=previous?.createdAt?:createdAt?:System.currentTimeMillis()
        var saved=result
        if(preserveExistingEvidence && previous!=null){
            val old=runCatching {JSONObject(previous.result)}.getOrDefault(JSONObject())
            val current=runCatching {JSONObject(result)}.getOrDefault(JSONObject())
            for(key in listOf("request","result","attempt","radio","identity","privacy","analysis")){
                if(current.optJSONObject(key)?.length()==0 && old.optJSONObject(key)!=null)current.put(key,old.getJSONObject(key))
            }
            saved=current.toString()
        }
        dao.putExperiment(ExperimentEntity(id,operation,state,redactResult(saved),created))
        val target=request?.let {NetworkWork.networkFromRequest(it)}?:runCatching {JSONObject(saved).optJSONObject("request")?.let {NetworkWork.networkFromRequest(it)}}.getOrNull()
        target?.let {linkWork(id,it)}
    }
    /** Encrypted ingestion does not require an extra authentication prompt. */
    suspend fun ingestCredential(secret:String,networkId:String?,label:String,username:String?,source:String,verification:String):String=withContext(Dispatchers.IO) {
        require(secret.isNotEmpty()) {"Enter a credential"}
        val id=UUID.randomUUID().toString(); val encrypted=vault.encrypt(secret,id)
        dao.putCredential(CredentialEntity(id,networkId,label,username,source,verification,System.currentTimeMillis(),encrypted.ciphertext,encrypted.iv,encrypted.wrappedKey));id
    }
    suspend fun saveWpsPin(secret:String,networkId:String?,label:String,experimentId:String,request:JSONObject,result:JSONObject)=db.withTransaction {
        ingestCredential(secret,networkId,label,null,"Pixiewps · imported exchange","Both WPS hashes verified; network enrollment untested")
        recordExperiment(experimentId,"wps_pixie","pin_verified",JSONObject().put("request",request).put("result",result).toString(),request)
    }
    suspend fun saveLiveWpsSecret(secret:String,bssid:String,session:String,kind:String,evidence:JSONObject)=db.withTransaction {
        require(kind in setOf("pin","password"))
        val network="ap:$bssid"
        val verification=if(kind=="pin") {
            if(evidence.optString("source")=="common_pin_trial") "AP supplied WPS settings; association untested"
            else "Both WPS hashes verified; no association test"
        } else "Received in authenticated WPS AP settings; association untested"
        ingestCredential(secret,network,if(kind=="pin")"WPS PIN · $bssid" else "Wi-Fi password · $bssid",null,"Managed WPS · $session",verification)
        val request=JSONObject().put("bssid",bssid)
        recordExperiment(session+"_"+kind,"wps_"+kind,"saved",JSONObject().put("request",request)
            .put("result",evidence.put("credential_saved",true).put("parent_session",session)).toString(),request)
    }
    suspend fun reconcileWps()=db.withTransaction {
        // Called once at process startup before any new solver may start.
        for(record in dao.interruptedWps()) {
            val evidence=runCatching {JSONObject(record.result)}.getOrDefault(JSONObject())
            val result=evidence.optJSONObject("result")?:JSONObject()
            evidence.put("result",result.put("state","interrupted").put("replayed",false))
            dao.putExperiment(record.copy(state="interrupted",result=redactResult(evidence.toString())))
        }
    }
    suspend fun revealCredential(id:String):String=withContext(Dispatchers.IO){
        val record=requireNotNull(dao.credential(id))
        val secret=vault.decrypt(record)
        if(vault.isLegacy(record)) {
            val encrypted=vault.encrypt(secret,record.id)
            // Update in place only after successful decryption and encryption.
            // A concurrent deletion must not resurrect an old credential.
            dao.migrateCredential(record.id,record.wrappedKey,encrypted.ciphertext,encrypted.iv,encrypted.wrappedKey)
        }
        secret
    }
    suspend fun deleteCredential(id:String)=dao.deleteCredential(id)
    suspend fun getSessionEvidence(sessionId:String):String=withContext(Dispatchers.IO) {
        val records=JSONArray();dao.observations().filter {it.sessionId==sessionId}.forEach {records.put(JSONObject(it.rawJson))}
        JSONObject().put("session_id",sessionId).put("observations",records).toString(2)
    }
    suspend fun exportEvidence():String=withContext(Dispatchers.IO) {
        val records=JSONArray();dao.observations().forEach { observation-> records.put(JSONObject().put("session_id",observation.sessionId).put("target_id",observation.targetId).put("kind",observation.kind).put("recorded_at",observation.recordedAt).put("evidence",JSONObject.wrap(runCatching<Any> { JSONObject(observation.rawJson) }.getOrElse { observation.rawJson }))) }
        val runs=JSONArray();dao.experimentSnapshot().forEach {runs.put(JSONObject().put("id",it.id).put("operation",it.operation).put("state",it.state).put("result",it.result).put("created_at",it.createdAt))}
        ShareRedactor().clean(JSONObject().put("export_schema_version",2).put("redacted",true).put("observations",records).put("experiments",runs)).put("notice","Selected typed metadata only. Vault, network names, raw bytes, free text, unknown fields and private paths excluded. Identifiers use per-export aliases. Timestamps and technical metadata can still be identifying.").toString(2)
    }
    private fun redactResult(value:String):String=runCatching { EvidenceJson.parseSanitized(value).toString() }.getOrElse { "Unstructured runtime output omitted from persistent evidence. Consult private session artifacts." }
    companion object {
        @Volatile private var instance:LabRepository?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:LabRepository(context).also {instance=it}}
        private val mac=Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        private val runtimeSession=Regex("bw_([0-9]+)_[0-9a-f]{12}")
        private fun earliest(a:Double?,b:Double)=listOfNotNull(a,b).filter {it>0&&it.isFinite()}.minOrNull()?:0.0
        private fun JSONObject.timestamp(key:String):Double {
            if(!has(key)||isNull(key)) return 0.0
            val value=optDouble(key,Double.NaN)
            require(value.isFinite()&&value>=0) {"Invalid observation timestamp"}
            return value
        }
        private fun JSONObject.intOrNull(key:String)=if(isNull(key)||!has(key))null else optInt(key)
        private fun JSONObject.nullableString(key:String)=if(isNull(key)||!has(key))null else optString(key)
    }
}
