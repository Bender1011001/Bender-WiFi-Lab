package dev.sir.benderwifi.radio
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class RadioAssessmentTest {
    @org.junit.Test fun recoveryCannotBeMarkedCheckedFromMissingOrMalformedRootOutput() {
        org.junit.Assert.assertFalse(RadioAssessment.rootStateObserved(org.json.JSONObject()))
        org.junit.Assert.assertFalse(RadioAssessment.rootStateObserved(org.json.JSONObject("""{"recovery_pending":false,"privacy_pending":"false"}""")))
        org.junit.Assert.assertTrue(RadioAssessment.rootStateObserved(org.json.JSONObject("""{"recovery_pending":false,"privacy_pending":true}""")))
    }
    @Test fun rootPrivacyRecoverySurvivesMissingAppHistoryAndConflictingReports(){
        val pending=JSONObject().put("privacy_pending",true)
        assertTrue(RadioAssessment.privacyAfter(false,"probe",true,JSONObject(),pending))
        assertTrue(RadioAssessment.privacyAfter(false,"identity",true,JSONObject().put("pending",false),pending))
        assertTrue(RadioAssessment.privacyAfter(true,"probe",true,JSONObject(),JSONObject().put("privacy_pending",false)))
        assertFalse(RadioAssessment.privacyAfter(true,"privacy_restore",true,JSONObject().put("pending",false).put("restored",true),JSONObject().put("privacy_pending",false)))
        assertFalse(RadioAssessment.privacyAfter(false,"probe",true,JSONObject(),JSONObject().put("privacy_pending","true")))
    }
    @Test fun intentOrMissingResultKeepsTransmissionUncertainRegardlessOfOldLabel(){
        assertEquals("uncertain",RadioAssessment.completion(1,JSONObject(),false,"lab",true,true))
        assertEquals("uncertain",RadioAssessment.completion(1,JSONObject(),false,"lab",false,true))
        assertEquals("uncertain",RadioAssessment.completion(130,JSONObject().put("ok",false),false,"lab",true,true))
        assertEquals("failed",RadioAssessment.completion(1,JSONObject().put("ok",false),false,"lab",false,true))
        assertEquals("completed",RadioAssessment.completion(0,JSONObject().put("ok",true),false,"lab",true,true))
    }
    @Test fun missingRuntimeAndFailedRechecksInvalidatePriorReadiness(){
        val ready=JSONObject().put("runtime_ready",true).toString()
        val missing=JSONObject().put("runtime_ready",false).put("message","Missing decoder")
        assertFalse(JSONObject(RadioAssessment.runtimeAfter(ready,"capture","failed",missing)!!).getBoolean("runtime_ready"))
        assertFalse(JSONObject(RadioAssessment.runtimeAfter(ready,"probe","failed",JSONObject())!!).getBoolean("runtime_ready"))
        assertEquals(ready,RadioAssessment.runtimeAfter(ready,"identity","failed",JSONObject()))
        assertTrue(JSONObject(RadioAssessment.runtimeAfter(null,"setup","completed",JSONObject(ready))!!).getBoolean("runtime_ready"))
    }
    @Test fun missingOrStringStateCannotProveRestoration() {
        assertFalse(RadioAssessment.restored(JSONObject()))
        assertFalse(RadioAssessment.restored(JSONObject("{\"managed_mode\":\"true\",\"recovery_pending\":false}")))
        assertFalse(RadioAssessment.restored(JSONObject("{\"managed_mode\":true,\"recovery_pending\":true}")))
    }
    @Test fun successNeedsIndependentRestorationRecord() {
        val ok=JSONObject("{\"ok\":true,\"matching_ack\":true}")
        assertEquals("recovery_required",RadioAssessment.completion(0,ok,true,"capture"))
        assertEquals("completed",RadioAssessment.completion(0,ok,false,"capture"))
        assertEquals("failed",RadioAssessment.completion(1,ok,false,"capture"))
        assertEquals("cancelled",RadioAssessment.completion(130,ok,false,"capture"))
    }
    @Test fun importedOriginsNeverAuthorizeRadioWork() {
        assertTrue(RadioAssessment.eligibleOrigin("bw_123456789_abcdef012345"))
        assertFalse(RadioAssessment.eligibleOrigin("imported:bw_123456789_abcdef012345"))
        assertFalse(RadioAssessment.eligibleOrigin("../bw_123456789_abcdef012345"))
    }
    @Test fun failedTransportDoesNotErasePossibleSubmission() {
        val result=JSONObject("{\"ok\":false,\"submission_may_have_occurred\":true}")
        assertEquals("uncertain",RadioAssessment.completion(1,result,false,"lab"))
    }
    @Test fun readOnlyWorkCannotInventOrEraseRecoveryObligations() {
        for(operation in listOf("probe","analyze","identity")) {
            assertFalse(RadioAssessment.recoveryAfter(false,operation,true,JSONObject()))
            assertTrue(RadioAssessment.recoveryAfter(true,operation,true,JSONObject()))
            assertEquals("completed",RadioAssessment.completion(0,JSONObject("{\"ok\":true}"),true,operation))
        }
    }
    @Test fun managedModeAloneDoesNotProveSavedSettingsRestored() {
        val observed=JSONObject("{\"managed_mode\":true,\"recovery_pending\":false,\"restoration_verified\":false}")
        assertTrue(RadioAssessment.managedAndClear(observed))
        assertFalse(RadioAssessment.restored(observed))
        assertTrue(RadioAssessment.recoveryAfter(true,"restore",true,observed))
        assertFalse(RadioAssessment.recoveryAfter(true,"restore",true,observed.put("restoration_verified",true)))
    }
    @Test fun failedLaunchIsDifferentFromUnknownRunningSupervisor() {
        assertFalse(RadioAssessment.recoveryAfter(false,"capture",false,JSONObject()))
        assertTrue(RadioAssessment.recoveryAfter(false,"capture",true,JSONObject()))
        assertTrue(RadioAssessment.recoveryAfter(false,"probe",true,JSONObject("{\"recovery_pending\":true}")))
    }
    @Test fun orderedRecoveryHistorySurvivesReadOnlySessionsAndClearsOnProof() {
        var pending=RadioAssessment.recoveryAfter(false,"capture",true,JSONObject())
        pending=RadioAssessment.recoveryAfter(pending,"analyze",true,JSONObject())
        pending=RadioAssessment.recoveryAfter(pending,"restore",true,JSONObject("{\"managed_mode\":true,\"recovery_pending\":false}"))
        assertTrue(pending)
        pending=RadioAssessment.recoveryAfter(pending,"restore",true,JSONObject("{\"managed_mode\":true,\"recovery_pending\":false,\"restoration_verified\":true}"))
        assertFalse(pending)
    }
    @Test fun privacyProfileNeedsSeparateDurableRecovery() {
        assertTrue(RadioAssessment.privacyAfter(false,"prepare",true,JSONObject()))
        assertFalse(RadioAssessment.privacyAfter(false,"prepare",false,JSONObject()))
        assertFalse(RadioAssessment.privacyAfter(false,"prepare",true,JSONObject("{\"pending\":false,\"state\":\"blocked\"}")))
        assertTrue(RadioAssessment.privacyAfter(true,"privacy_restore",true,JSONObject()))
        assertTrue(RadioAssessment.privacyAfter(true,"identity",true,JSONObject("{\"pending\":false}")))
        assertFalse(RadioAssessment.privacyAfter(true,"privacy_restore",true,JSONObject("{\"pending\":false,\"restored\":true}")))
        assertFalse(RadioAssessment.recoveryAfter(false,"prepare",true,JSONObject()))
    }
    @Test fun explicitRetryDoesNotChangeAnUncertainRecordedOutcome() {
        assertEquals("uncertain",RadioAssessment.completion(130,JSONObject(),false,"lab",true,true))
        assertEquals("uncertain",RadioAssessment.completion(1,JSONObject(),false,"lab",false,true))
    }
}
