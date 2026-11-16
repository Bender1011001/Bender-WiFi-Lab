package dev.sir.benderwifi.ui

import dev.sir.benderwifi.data.ClientEntity
import dev.sir.benderwifi.data.NetworkEntity
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TargetAuthorizationTest {
    private val now=System.currentTimeMillis()/1000.0
    private val ap=NetworkEntity("ap:02:00:00:00:00:01","02:00:00:00:00:01","TEST ONLY","unknown",1,2412,-50,"unknown",now,now,"live-session","{}")
    private val client=ClientEntity("sta:02:00:00:00:00:02","02:00:00:00:00:02",ap.id,-60,now,now,ap.sessionId,"{}")
    @Test fun importedAndOldSelectionCanBeUsedWithoutFreshCapture(){
        assertNull(labBlocker(ap.copy(sessionId="imported:test",lastSeen=0.0),client.copy(sessionId="other",lastSeen=0.0),false,false))
    }
    @Test fun selectedClientStillBelongsToSelectedDossier(){
        assertNotNull(labBlocker(ap,client.copy(apId="other-ap"),false,false))
    }
    @Test fun activeOperationRecoveryAndMissingFrequencyBlock(){
        assertNotNull(labBlocker(ap,client,true,false));assertNotNull(labBlocker(ap,client,false,true))
        assertNotNull(labBlocker(ap.copy(frequencyMhz=null),client,false,false))
    }
    @Test fun surveySelectionWorksWithoutTargetedCapture(){assertNull(labBlocker(ap,client,false,false))}
    @Test fun targetSelectionIsRequired(){assertNotNull(labBlocker(ap,null,false,false));assertNotNull(labBlocker(null,client,false,false))}
    @Test fun workshopOperationsHaveWorkingDestinations(){
        for(op in listOf("setup","probe","restore","identity","prepare","privacy_restore","privacy_status"))assertEquals("run",workshopDestination(op))
        assertEquals("import",workshopDestination("analyze"))
        assertEquals("radar",workshopDestination("capture"));assertEquals("dossiers",workshopDestination("lab"))
        assertEquals("prerequisites",workshopDestination(null));assertEquals("prerequisites",workshopDestination("unknown"))
    }
}
