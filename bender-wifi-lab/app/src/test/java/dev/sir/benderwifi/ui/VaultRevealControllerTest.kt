package dev.sir.benderwifi.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VaultRevealControllerTest {
    @Test fun decryptFinishingAfterStopAndResumeCannotReveal()=runBlocking {
        var foreground=true;var displayed:String?=null
        val controller=VaultRevealController({foreground},{displayed=it})
        val decrypt=CompletableDeferred<String>()
        val pending=launch(start=CoroutineStart.UNDISPATCHED) {controller.reveal {decrypt.await()}}
        foreground=false;controller.lock();foreground=true
        decrypt.complete("STALE SYNTHETIC SECRET");pending.join()
        assertNull(displayed)
        controller.reveal {"FRESH SYNTHETIC SECRET"}
        assertEquals("FRESH SYNTHETIC SECRET",displayed)
        controller.lock();assertNull(displayed)
    }
    @Test fun backgroundCompletionAndOlderOverlappingRequestsStayHidden()=runBlocking {
        var foreground=true;var displayed:String?=null
        val controller=VaultRevealController({foreground},{displayed=it})
        val old=CompletableDeferred<String>()
        val pending=launch(start=CoroutineStart.UNDISPATCHED) {controller.reveal {old.await()}}
        controller.reveal {"NEW"}
        old.complete("OLD");pending.join();assertEquals("NEW",displayed)
        controller.lock();foreground=false
        var decrypted=false
        controller.reveal {decrypted=true;"BACKGROUND"}
        assertFalse(decrypted);assertNull(displayed)
    }
}
