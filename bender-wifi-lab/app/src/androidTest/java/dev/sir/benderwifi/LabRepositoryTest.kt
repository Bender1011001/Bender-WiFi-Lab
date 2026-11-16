package dev.sir.benderwifi

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.LabDatabase
import dev.sir.benderwifi.data.LabRepository
import dev.sir.benderwifi.data.SecretVault
import dev.sir.benderwifi.radio.importSavedObservations
import java.io.File
import android.app.KeyguardManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Synthetic records stay in an isolated in-memory test database, never the app's database. */
@RunWith(AndroidJUnit4::class)
class LabRepositoryTest {
    private fun fixture(time:Long,label:String="SYNTHETIC")="""{"schema_version":1,"networks":[{"id":"ap:02:00:00:00:00:08","bssid":"02:00:00:00:00:08","ssid_display":"$label","last_seen":$time}],"clients":[{"id":"sta:02:00:00:00:00:09","mac":"02:00:00:00:00:09","ap_id":"ap:02:00:00:00:00:08","last_seen":$time}]}"""

    @Test fun futureDatedImportCannotDisplaceLiveInEitherOrder()=runBlocking {
        for(importFirst in listOf(true,false)) {
            val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
            try {
                val repo=LabRepository(db)
                suspend fun historical()=repo.importObservations(fixture(4102444800L,"FUTURE IMPORT"),"imported:future")
                suspend fun live()=repo.importObservations(fixture(1700000000L,"LIVE"),"bw_1700000000000_000000000001")
                if(importFirst){historical();live()}else{live();historical()}
                assertEquals("LIVE",repo.networks.first().single().ssidDisplay)
                assertFalse(repo.clients.first().single().sessionId.startsWith("imported:"))
                assertEquals(4,db.dao().observations().size)
                assertTrue(repo.getSessionEvidence("imported:future").contains("4102444800"))
            }finally {db.close()}
        }
    }
    @Test fun liveCaptureOrderDoesNotDependOnPacketClockOrReplayOrder()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            repo.importObservations(fixture(1700000000,"NEWER"),"bw_2000_000000000001")
            repo.importObservations(fixture(4102444800,"OLDER"),"bw_1000_000000000001")
            assertEquals("NEWER",repo.networks.first().single().ssidDisplay)
            assertEquals("bw_2000_000000000001",repo.clients.first().single().sessionId)
        }finally {db.close()}
    }
    @Test fun completedArtifactsSurviveProcessDeathAndRepeatedReconciliation()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val parent=File(context.cacheDir,"reconcile-test-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        try {
            val job=File(parent,"bw_1700000000000_000000000001").apply {mkdir()}
            File(job,"analysis-state.json").writeText("""{"status":"complete"}""")
            File(job,"observations-v1.json").writeText(fixture(1700000000))
            // No exit or final journal: model death after the supervisor committed output.
            val repo=LabRepository(db)
            repeat(2){assertNull(importSavedObservations(job,"capture",repo::importObservations))}
            assertEquals(1,repo.sessions.first().size);assertEquals(2,db.dao().observations().size)
            assertEquals(job.name,repo.networks.first().single().sessionId)
            val imported=File(parent,"bw_1700000000001_000000000001").apply {mkdir()}
            File(imported,"analysis-state.json").writeText("""{"status":"complete"}""")
            File(imported,"observations-v1.json").writeText(fixture(4102444800))
            assertNull(importSavedObservations(imported,"analyze",repo::importObservations))
            assertTrue(repo.sessions.first().any {it.id=="imported:${imported.name}"})
            assertEquals(job.name,repo.networks.first().single().sessionId)
        }finally {db.close();parent.deleteRecursively()}
    }
    @Test fun incompleteOrMalformedSavedOutputDoesNotCommitAndCanBeRetried()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val job=File(context.cacheDir,"incomplete-test-${java.util.UUID.randomUUID()}").apply {mkdir()}
        try {
            val repo=LabRepository(db)
            val artifact=File(job,"observations-v1.json").apply {writeText(fixture(1))}
            assertNotNull(importSavedObservations(job,"capture",repo::importObservations))
            assertTrue(repo.sessions.first().isEmpty())
            File(job,"analysis-state.json").writeText("""{"status":"complete"}""")
            artifact.writeText("{broken")
            assertNotNull(importSavedObservations(job,"capture",repo::importObservations))
            assertTrue(repo.sessions.first().isEmpty())
            artifact.writeText(fixture(1))
            assertNull(importSavedObservations(job,"capture",repo::importObservations))
            assertEquals(1,repo.sessions.first().size)
        }finally {db.close();job.deleteRecursively()}
    }
    @Test fun invalidTimestampsCannotPoisonTheInventory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            for(value in listOf("-1","\"NaN\"","\"Infinity\"","\"bad\"")) {
                val json=fixture(10).replace("\"last_seen\":10","\"last_seen\":$value")
                assertTrue(runCatching {repo.importObservations(json,"imported:invalid")}.isFailure)
                assertTrue(repo.sessions.first().isEmpty());assertTrue(repo.networks.first().isEmpty())
            }
        }finally {db.close()}
    }
    @Test fun embeddedCredentialPayloadsNeverReachRoomOrShareExport()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            val nested=JSONObject().put("network_key","NETWORK SECRET").put("wps-pin","PIN SECRET").put("anonce","NONCE SECRET").put("eapol_mic_hex","MIC SECRET").put("key_data_hex","KEYDATA SECRET")
            val fixture=JSONObject("""{"schema_version":1,"networks":[{"id":"ap:02:00:00:00:00:04","bssid":"02:00:00:00:00:04","ssid_display":"SYNTHETIC TEST ONLY"}]}""")
            fixture.getJSONArray("networks").getJSONObject(0).put("embedded_json",nested.toString())
            fixture.put("source",nested)
            repo.importObservations(fixture.toString(),"imported:nested-secret-test")
            repo.recordExperiment("nested-result-test","test","complete",JSONObject().put("embedded",nested.toString()).toString())
            val persisted=repo.networks.first().joinToString()+repo.sessions.first().joinToString()+db.dao().observations().joinToString()+repo.experiments.first().joinToString()+repo.exportEvidence()
            listOf("NETWORK SECRET","PIN SECRET","NONCE SECRET","MIC SECRET","KEYDATA SECRET").forEach {assertFalse(persisted.contains(it))}
        }finally {db.close()}
    }
    @Test fun automaticVaultIngestionEncryptsWithoutAuthentication()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires configured device credential; never changes device lock settings",context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db,SecretVault(context))
            val secret="SYNTHETIC-SECRET-"+java.util.UUID.randomUUID()
            repo.ingestCredential(secret,null,"SYNTHETIC TEST ONLY",null,"instrumentation","unverified")
            val record=repo.credentials.first().single()
            assertNotEquals(secret,record.ciphertext)
            assertTrue(record.wrappedKey.isNotBlank());assertTrue(record.iv.isNotBlank())
            assertFalse(repo.exportEvidence().contains(secret))
        }finally {db.close()}
    }
    @Test fun importRetainsHistoryAndRedactsSecrets()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            val fixture="""{"schema_version":1,"source":{"decoder":"SYNTHETIC TEST ONLY"},"networks":[{"id":"ap:02:00:00:00:00:01","bssid":"02:00:00:00:00:01","ssid_display":"SYNTHETIC TEST ONLY","last_seen":123,"password":"must-never-survive"}],"clients":[],"observations":[],"handshakes":[],"limits":["Synthetic fixture"]}"""
            repo.importObservations(fixture,"imported:test-one")
            repo.importObservations(fixture,"imported:test-two")
            assertEquals(1,repo.networks.first().size)
            assertEquals(2,repo.sessions.first().size)
            val export=repo.exportEvidence()
            assertFalse(export.contains("must-never-survive"))
            assertEquals(2,JSONObject(export).getJSONArray("observations").length())
            assertFalse(JSONObject(export).has("credentials"))
            assertTrue(repo.getSessionEvidence("imported:test-one").contains("SYNTHETIC TEST ONLY"))
            assertTrue(repo.credentials.first().isEmpty())
        } finally {db.close()}
    }
    @Test fun invalidImportRollsBackWholeSession()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            val bad="""{"schema_version":1,"networks":[{"id":"ap:02:00:00:00:00:01","bssid":"02:00:00:00:00:01"},{"id":"wrong","bssid":"invalid"}]}"""
            assertTrue(runCatching {repo.importObservations(bad,"imported:invalid")}.isFailure)
            assertTrue(repo.networks.first().isEmpty());assertTrue(repo.sessions.first().isEmpty())
        }finally {db.close()}
    }
    @Test fun olderImportPreservesLatestCaptureAndExtendsHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            fun fixture(first:Int,last:Int,ssid:String)="""{"schema_version":1,"networks":[{"id":"ap:02:00:00:00:00:03","bssid":"02:00:00:00:00:03","ssid_display":"$ssid","first_seen":$first,"last_seen":$last}]}"""
            repo.importObservations(fixture(100,200,"SYNTHETIC LATEST"),"live-test-session")
            repo.importObservations(fixture(50,150,"SYNTHETIC OLD"),"imported:old")
            val current=repo.networks.first().single()
            assertEquals("live-test-session",current.sessionId)
            assertEquals("SYNTHETIC LATEST",current.ssidDisplay)
            assertEquals(50.0,current.firstSeen,0.0);assertEquals(200.0,current.lastSeen,0.0)
            assertTrue(repo.getSessionEvidence("imported:old").contains("SYNTHETIC OLD"))
        }finally {db.close()}
    }
}
