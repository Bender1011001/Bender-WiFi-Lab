package dev.sir.benderwifi

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.*
import dev.sir.benderwifi.radio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offline recovery fixtures; these tests never start a root worker. */
class ReviewRecoveryTest {
    @Test fun terminalFilesRepairRunningRowsAndBackfillLegacyTargetsWithoutErasingEvidence()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val repo=LabRepository(db)
        val job=File(context.cacheDir,"recovery-${UUID.randomUUID()}").apply {mkdir()}
        val target="ap:02:00:00:00:00:41"
        try {
            // Old v1 rows did not embed the target request. No observation is needed.
            repo.recordExperiment(job.name,"lab","running","{}",createdAt=1234)
            File(job,"journal.json").writeText("""{"state":"completed","created_ms":5678}""")
            File(job,"request.json").writeText("""{"ap":"02:00:00:00:00:41"}""")
            File(job,"result.json").writeText("""{"ok":true,"matching_ack":true}""")
            File(job,"radio-state.json").writeText("""{"restoration_verified":true}""")
            reconcileSavedExperiment(job,"lab","completed",null,repo)
            val saved=repo.networkExperiments(target).first().single()
            assertEquals("completed",saved.state);assertEquals(1234L,saved.createdAt)
            assertTrue(JSONObject(saved.result).getJSONObject("result").getBoolean("matching_ack"))
            // A second replay with missing files must retain previously committed detail.
            File(job,"result.json").writeText("broken json")
            File(job,"request.json").delete()
            reconcileSavedExperiment(job,"lab","uncertain",null,repo)
            val replayed=repo.networkExperiments(target).first().single()
            assertEquals("uncertain",replayed.state);assertEquals(1234L,replayed.createdAt)
            assertEquals("02:00:00:00:00:41",JSONObject(replayed.result).getJSONObject("request").getString("ap"))
            assertTrue(JSONObject(replayed.result).getJSONObject("result").getBoolean("matching_ack"))
            assertEquals(1,repo.experiments.first().size)
        }finally{db.close();job.deleteRecursively()}
    }
    @Test fun clientOnlyCaptureLinksToItsNetworkWorkspace()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            repo.importObservations("""{"schema_version":1,"networks":[],"clients":[{"id":"sta:02:00:00:00:00:42","mac":"02:00:00:00:00:42","ap_id":"ap:02:00:00:00:00:41"}],"handshakes":[]}""","bw_1000_000000000041")
            assertEquals(1,repo.networkSessions("ap:02:00:00:00:00:41").first().size)
            assertTrue(repo.networkSessions("ap:02:00:00:00:00:99").first().isEmpty())
            assertTrue(repo.networks.first().isEmpty())
        }finally{db.close()}
    }
    @Test fun importReservesControllerAndCancellationClosesTheSource()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        RadioController.init(context)
        withTimeout(10_000){RadioController.state.first {!it.busy}}
        val entered=CountDownLatch(1);val closed=CountDownLatch(1)
        val source=object:InputStream(){
            override fun read():Int=error("bulk read expected")
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                entered.countDown();check(closed.await(10,TimeUnit.SECONDS));return -1
            }
            override fun close(){closed.countDown()}
        }
        try {
            RadioController.copyForAnalysis {source}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(RadioController.state.value.busy)
            assertEquals("import",RadioController.state.value.operation)
            assertNotNull(RadioController.reserveBluetooth("synthetic-competing-bluetooth"))
            // These must not invoke root or open a second document.
            RadioController.start("probe")
            var openedAgain=false
            RadioController.copyForAnalysis {openedAgain=true;source}
            assertFalse(openedAgain);assertEquals("import",RadioController.state.value.operation)
            RadioController.cancel()
            assertTrue(closed.await(5,TimeUnit.SECONDS))
            withTimeout(10_000){RadioController.state.first {!it.busy}}
            assertTrue(RadioController.state.value.phase.contains("cancelled",ignoreCase=true))
            assertTrue(File(context.filesDir,"imports").listFiles().orEmpty().isEmpty())
        }finally {RadioController.cancel();source.close()}
    }
}
