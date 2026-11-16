package dev.sir.benderwifi.radio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class SessionCompletionTest {
    @Test fun blockedTeardownCannotPublishAvailabilityForAnotherStart() {
        val busy=AtomicBoolean(true);val stopping=CountDownLatch(1);val stopped=CountDownLatch(1)
        val worker=Thread {finishRadioSession({stopping.countDown();check(stopped.await(3,TimeUnit.SECONDS))},{busy.set(false)})}
        worker.start()
        try {assertTrue(stopping.await(3,TimeUnit.SECONDS));assertFalse(busy.compareAndSet(false,true))}
        finally {stopped.countDown();worker.join(3000)}
        assertFalse(worker.isAlive);assertTrue(busy.compareAndSet(false,true))
    }
    @Test fun teardownFailureStillReleasesSession() {
        var released=false
        assertTrue(runCatching {finishRadioSession({error("service already destroyed")},{released=true})}.isFailure)
        assertTrue(released)
    }
}
