package dev.sir.benderwifi.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

@Entity(tableName = "networks")
data class NetworkEntity(@PrimaryKey val id:String, val bssid:String, val ssidDisplay:String, val security:String, val channel:Int?, val frequencyMhz:Int?, val signalDbm:Int?, val pmfAdvertised:String, val firstSeen:Double, val lastSeen:Double, val sessionId:String, val rawJson:String)
@Entity(tableName = "clients")
data class ClientEntity(@PrimaryKey val id:String, val mac:String, val apId:String?, val signalDbm:Int?, val firstSeen:Double, val lastSeen:Double, val sessionId:String, val rawJson:String)
@Entity(tableName = "experiments")
data class ExperimentEntity(@PrimaryKey val id:String, val operation:String, val state:String, val result:String, val createdAt:Long)
@Entity(tableName = "credentials")
data class CredentialEntity(@PrimaryKey val id:String, val networkId:String?, val label:String, val username:String?, val source:String, val verification:String, val createdAt:Long, val ciphertext:String, val iv:String, val wrappedKey:String)
@Entity(tableName = "sessions")
data class SessionEntity(@PrimaryKey val id:String, val origin:String, val importedAt:Long, val sourceJson:String, val limitsJson:String)
@Entity(tableName = "observations", indices = [Index("sessionId"), Index("targetId")])
data class ObservationEntity(@PrimaryKey(autoGenerate = true) val id:Long=0, val sessionId:String, val targetId:String?, val kind:String, val rawJson:String, val recordedAt:Long)
@Entity(tableName="work_targets",primaryKeys=["sessionId","networkId"],indices=[Index("networkId")])
data class WorkTargetEntity(val sessionId:String,val networkId:String)
@Entity(tableName="network_notes")
data class NetworkNoteEntity(@PrimaryKey val networkId:String,val text:String,val updatedAt:Long)

@Dao
interface LabDao {
    @Query("SELECT * FROM networks ORDER BY lastSeen DESC") fun networks():Flow<List<NetworkEntity>>
    @Query("SELECT * FROM clients ORDER BY lastSeen DESC") fun clients():Flow<List<ClientEntity>>
    @Query("SELECT * FROM experiments ORDER BY createdAt DESC") fun experiments():Flow<List<ExperimentEntity>>
    @Query("SELECT * FROM credentials ORDER BY createdAt DESC") fun credentials():Flow<List<CredentialEntity>>
    @Query("SELECT * FROM sessions ORDER BY importedAt DESC") fun sessions():Flow<List<SessionEntity>>
    @Query("SELECT * FROM networks WHERE id=:id") suspend fun network(id:String):NetworkEntity?
    @Query("SELECT * FROM clients WHERE id=:id") suspend fun client(id:String):ClientEntity?
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id:String):SessionEntity?
    @Query("SELECT * FROM experiments WHERE id=:id") suspend fun experiment(id:String):ExperimentEntity?
    @Query("SELECT * FROM experiments WHERE operation='wps_pixie' AND state='running'") suspend fun interruptedWps():List<ExperimentEntity>
    @Query("SELECT * FROM experiments WHERE id IN (SELECT sessionId FROM work_targets WHERE networkId=:id) ORDER BY createdAt DESC") fun networkExperiments(id:String):Flow<List<ExperimentEntity>>
    @Query("SELECT * FROM sessions WHERE id IN (SELECT sessionId FROM work_targets WHERE networkId=:id) ORDER BY importedAt DESC") fun networkSessions(id:String):Flow<List<SessionEntity>>
    @Query("SELECT * FROM observations WHERE kind='handshakes' AND targetId=:id ORDER BY recordedAt DESC,id DESC") fun networkHandshakes(id:String):Flow<List<ObservationEntity>>
    @Query("SELECT * FROM network_notes WHERE networkId=:id") fun networkNote(id:String):Flow<NetworkNoteEntity?>
    @Query("SELECT * FROM credentials WHERE id=:id") suspend fun credential(id:String):CredentialEntity?
    @Query("SELECT * FROM observations ORDER BY id DESC") suspend fun observations():List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE sessionId=:sessionId AND kind IN ('network','client') ORDER BY id") fun sessionInventory(sessionId:String):Flow<List<ObservationEntity>>
    @Query("SELECT * FROM experiments ORDER BY createdAt DESC") suspend fun experimentSnapshot():List<ExperimentEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putNetwork(value:NetworkEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putClient(value:ClientEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putExperiment(value:ExperimentEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putCredential(value:CredentialEntity)
    @Query("UPDATE credentials SET ciphertext=:ciphertext, iv=:iv, wrappedKey=:wrappedKey WHERE id=:id AND wrappedKey=:previousKey")
    suspend fun migrateCredential(id:String,previousKey:String,ciphertext:String,iv:String,wrappedKey:String):Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSession(value:SessionEntity)
    @Insert suspend fun putObservation(value:ObservationEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putTarget(value:WorkTargetEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putNote(value:NetworkNoteEntity)
    @Query("DELETE FROM credentials WHERE id=:id") suspend fun deleteCredential(id:String)
}
@Database(entities=[NetworkEntity::class,ClientEntity::class,ExperimentEntity::class,CredentialEntity::class,SessionEntity::class,ObservationEntity::class,WorkTargetEntity::class,NetworkNoteEntity::class],version=2,exportSchema=false)
abstract class LabDatabase:RoomDatabase(){
    abstract fun dao():LabDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS work_targets (sessionId TEXT NOT NULL, networkId TEXT NOT NULL, PRIMARY KEY(sessionId,networkId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_targets_networkId ON work_targets(networkId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS network_notes (networkId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                // Derive links from saved evidence without changing radio eligibility.
                db.query("SELECT id,sessionId,kind,rawJson FROM observations").use { cursor ->
                    while(cursor.moveToNext()) {
                        val value=runCatching {JSONObject(cursor.getString(3))}.getOrNull()?:continue
                        val network=NetworkWork.networkFromObservation(cursor.getString(2),value)?:continue
                        val session=cursor.getString(1)
                        for(origin in NetworkWork.origins(session)) db.execSQL("INSERT OR IGNORE INTO work_targets(sessionId,networkId) VALUES(?,?)",arrayOf(origin,network))
                        if(cursor.getString(2)=="handshakes") db.execSQL("UPDATE observations SET targetId=? WHERE id=?",arrayOf(network,cursor.getLong(0)))
                    }
                }
            }
        }
    }
}
