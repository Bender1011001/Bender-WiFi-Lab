package dev.sir.benderwifi

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic in-memory Room tests. No real database, vault, root or radio use. */
class CorrectiveEvidenceTest {
    @Test fun aliasSecretsAreDroppedBeforeRoomAndLegacyRowsAreSafeToExport()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LabDatabase::class.java).build()
        try {
            val repo=LabRepository(db)
            val network="""{"id":"ap:02:00:00:00:00:01","bssid":"02:00:00:00:00:01","ssid_display":"SYNTHETIC NAME","wifi_key":"SYNTHETIC SECRET","essid":"SYNTHETIC ALIAS","security":"WPA2-Personal","security_quality":"decoded","security_variants":[{"label":"WPA2-Personal","protocols":["RSN"],"authentication":["PSK"],"pairwise_ciphers":["CCMP-128"],"group_ciphers":["CCMP-128"],"pmf":["capable"],"quality":"decoded"}]}"""
            repo.importObservations("""{"schema_version":1,"networks":[$network],"source":{"frame_integrity":{"failed":2,"verified":0,"not_flagged":3,"unknown":1}}}""","bw_123_012345abcdef")
            assertFalse(db.dao().observations().single().rawJson.contains("SYNTHETIC SECRET"))
            assertFalse(repo.networks.first().single().rawJson.contains("SYNTHETIC ALIAS"))
            assertEquals("WPA2-Personal",repo.networks.first().single().security)
            // Model a stored pre-upgrade record that bypassed the old import filter.
            db.dao().putObservation(ObservationEntity(sessionId="bw_123_012345abcdef",targetId="ap:02:00:00:00:00:01",kind="network",rawJson=network,recordedAt=1))
            repo.recordExperiment("bw_123_012345abcdef","capture","completed","{\"ok\":true}")
            val shared=repo.exportEvidence()
            for(secret in listOf("SYNTHETIC SECRET","SYNTHETIC NAME","SYNTHETIC ALIAS","02:00:00:00:00:01"))assertFalse(shared.contains(secret))
            val json=JSONObject(shared)
            assertEquals(2,json.getInt("export_schema_version"))
            assertEquals(json.getJSONArray("experiments").getJSONObject(0).getString("id"),json.getJSONArray("observations").getJSONObject(0).getString("session_id"))
            assertEquals(2,JSONObject(repo.sessions.first().single().sourceJson).getJSONObject("frame_integrity").getInt("failed"))
        } finally {db.close()}
    }
}
