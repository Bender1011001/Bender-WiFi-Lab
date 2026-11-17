package dev.sir.benderwifi.wps

import org.junit.Assert.*
import org.junit.Test

class WpsProgressTest {
    @Test fun heartbeatDoesNotInventProtocolProgressOrExtendDeadline(){
        val start=WpsProgress("one",1000).event("collecting_exchange","Waiting for AP",2000,45_000)
        val beat=start.heartbeat(15_000,"Radio worker")
        assertEquals(start.events,beat.events)
        assertEquals(start.lastEventMs,beat.lastEventMs)
        assertEquals(start.deadlineMs,beat.deadlineMs)
        assertFalse(beat.stalled(16_000))
        assertTrue(beat.stalled(26_000))
    }
    @Test fun stagesClearPreviousWorkerAndFinishedTimeStops(){
        val start=WpsProgress("one",1000).heartbeat(2000,"Radio worker")
        val solve=start.event("solving","Local search",3000,120_000)
        assertNull(solve.worker)
        assertNull(solve.heartbeatMs)
        val done=solve.finish("No PIN found",9000)
        assertEquals(8L,done.elapsed(999_000))
        assertFalse(done.stalled(999_000))
        assertEquals(done,done.heartbeat(10_000,"Late worker"))
        assertEquals(done,done.event("solving","Late reply",10_000))
    }
    @Test fun freshEventClearsStaleHeartbeatWarning(){
        val p=WpsProgress("one",1000).heartbeat(2000,"Radio worker").event("setup","Root answered",20_000)
        assertFalse(p.stalled(21_000))
    }
    @Test fun timelineIsBoundedAndExportUsesRelativeTimes(){
        var p=WpsProgress("one",1000)
        repeat(100){p=p.event("setup","Known event",1000+it.toLong())}
        p=p.finish("Finished",2000)
        assertEquals(80,p.events.size)
        val json=p.json()
        assertEquals(1,json.getLong("elapsed_seconds"))
        assertEquals(1000,json.getJSONArray("events").getJSONObject(79).getLong("elapsed_ms"))
        assertFalse(json.toString().contains("heartbeat"))
    }
    @Test fun oldSessionCannotOverwriteNewSession(){
        WpsProgressStore.begin("old")
        WpsProgressStore.begin("new")
        val expected=WpsProgressStore.state.value
        WpsProgressStore.event("old","solving","Old event")
        WpsProgressStore.heartbeat("old","Old worker")
        WpsProgressStore.finish("old","Old finish")
        assertEquals(expected,WpsProgressStore.state.value)
    }
}
