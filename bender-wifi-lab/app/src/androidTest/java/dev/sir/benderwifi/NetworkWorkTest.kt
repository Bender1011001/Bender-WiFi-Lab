package dev.sir.benderwifi

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NetworkWorkPersistenceTest {
    private val ap="ap:02:00:00:00:00:21"
    private val session="bw_1000_000000000001"
    private val fixture="""{"schema_version":1,"networks":[{"id":"ap:02:00:00:00:00:21","bssid":"02:00:00:00:00:21","ssid_display":"SYNTHETIC","last_seen":1000}],"handshakes":[{"ap_id":"ap:02:00:00:00:00:21","client_id":"sta:02:00:00:00:00:22","messages":[1,2],"coverage":"partial","password_verified":false}]}"""
    @Test fun workAndNotesSurviveReopenWithoutLinkingAnotherNetwork()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="workspace-test-${UUID.randomUUID()}"
        var db=Room.databaseBuilder(context,LabDatabase::class.java,name).addMigrations(LabDatabase.MIGRATION_1_2).build()
        try {
            var repo=LabRepository(db)
            repo.importObservations(fixture,session)
            repo.recordExperiment(session,"capture","running","{}",JSONObject().put("bssid",ap.removePrefix("ap:")))
            val created=repo.networkExperiments(ap).first().single().createdAt
            repo.recordExperiment(session,"capture","completed","{}")
            assertEquals(created,repo.networkExperiments(ap).first().single().createdAt)
            repo.saveNote(ap,"Synthetic note")
            db.close()
            db=Room.databaseBuilder(context,LabDatabase::class.java,name).addMigrations(LabDatabase.MIGRATION_1_2).build()
            repo=LabRepository(db)
            assertEquals("Synthetic note",repo.networkNote(ap).first()!!.text)
            assertEquals(1,repo.networkSessions(ap).first().size)
            assertEquals(1,repo.networkHandshakes(ap).first().size)
            assertEquals("completed",repo.networkExperiments(ap).first().single().state)
            assertTrue(repo.networkExperiments("ap:02:00:00:00:00:99").first().isEmpty())
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun migrationBackfillsOldCaptureAndHandshakeLinks()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="workspace-migration-${UUID.randomUUID()}"
        var db=Room.databaseBuilder(context,LabDatabase::class.java,name).build()
        try {
            LabRepository(db).importObservations(fixture,session)
            db.close()
            // The six unchanged tables form the exact v1 schema. Remove only v2 additions.
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {old->
                old.execSQL("DROP TABLE work_targets");old.execSQL("DROP TABLE network_notes")
                old.execSQL("UPDATE observations SET targetId=NULL WHERE kind='handshakes'")
                old.execSQL("UPDATE room_master_table SET identity_hash='v1-migration-fixture'")
                old.version=1
            }
            db=Room.databaseBuilder(context,LabDatabase::class.java,name).addMigrations(LabDatabase.MIGRATION_1_2).build()
            val repo=LabRepository(db)
            assertEquals(1,repo.networkSessions(ap).first().size)
            assertEquals(1,repo.networkHandshakes(ap).first().size)
            assertEquals("SYNTHETIC",repo.getNetwork(ap)!!.ssidDisplay)
            assertNull(repo.networkNote(ap).first())
        }finally{db.close();context.deleteDatabase(name)}
    }
}
