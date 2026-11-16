package dev.sir.benderwifi

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import dev.sir.benderwifi.wps.*
import dev.sir.benderwifi.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real bundled Android executable and Keystore, public synthetic data; no radio. */
class PixieNativeTest {
    @Test fun bundledSolverRecoversNonChecksummedPinAndStoresEncryptedNetworkResult()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val exchange=PixieProtocol.parse(instrumentation.context.assets.open("pixie-synthetic.json").use(PixieProtocol::read))
        val pin=PixieRunner.solve(File(context.applicationInfo.nativeLibraryDir,"libbender_pixie.so"),exchange,30_000)
        assertEquals("12345678",pin)
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val repo=LabRepository(db,SecretVault(context))
        try {
            val ap="ap:02:00:00:00:00:51"
            repo.importObservations("""{"schema_version":1,"networks":[{"id":"$ap","bssid":"02:00:00:00:00:51","ssid_display":"SYNTHETIC PIXIE"}],"clients":[],"handshakes":[]}""","imported:pixie-fixture")
            repo.saveWpsPin(pin!!,ap,"Synthetic WPS PIN","wps-fixture",JSONObject().put("ap",ap),JSONObject().put("state","pin_verified").put("enrollment_performed",false))
            val stored=repo.credentials.first().single()
            assertEquals(pin,repo.revealCredential(stored.id))
            assertEquals(ap,stored.networkId)
            val work=repo.networkExperiments(ap).first()
            assertEquals(1,work.size)
            assertFalse(work.single().result.contains(pin))
            assertFalse(work.single().result.contains("authkey"))
            repo.recordExperiment("wps-interrupted","wps_pixie","running","{\"result\":{\"state\":\"running\"}}",JSONObject().put("ap",ap))
            repo.reconcileWps()
            val recovered=repo.networkExperiments(ap).first().associateBy {it.id}
            assertEquals("interrupted",recovered.getValue("wps-interrupted").state)
            assertEquals("pin_verified",recovered.getValue("wps-fixture").state)
        }finally{db.close()}
    }
    @Test fun nativeSearchHasEnforcedWallDeadline()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val json=JSONObject(instrumentation.context.assets.open("pixie-synthetic.json").use(PixieProtocol::read))
            .put("e_hash1","ff".repeat(32)).put("e_hash2","ee".repeat(32))
        val binary=File(instrumentation.targetContext.applicationInfo.nativeLibraryDir,"libbender_pixie.so")
        try{PixieRunner.solve(binary,PixieProtocol.parse(json.toString()),1);fail("Deadline should cancel")}
        catch(_:TimeoutCancellationException){/* The real child must be destroyed before returning. */}
    }
}
