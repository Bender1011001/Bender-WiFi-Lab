package dev.sir.benderwifi.ui

import org.junit.Assert.*
import org.junit.Test

class NotificationAccessTest {
    @Test fun firstRequestAndRationaleUsePermissionButPermanentDenialUsesSettings(){
        val blocked=NotificationAccessStatus(true,false,false)
        assertFalse(blocked.enabled)
        assertEquals("permission",blocked.destination(false,false))
        assertEquals("permission",blocked.destination(true,true))
        assertEquals("app_settings",blocked.destination(true,false))
    }
    @Test fun appAndChannelBlocksRemainDistinctEvenWithPermissionGranted(){
        val appBlocked=NotificationAccessStatus(false,false,false)
        val channelBlocked=NotificationAccessStatus(false,true,true)
        assertFalse(appBlocked.enabled);assertEquals("app_settings",appBlocked.destination(true,false))
        assertFalse(channelBlocked.enabled);assertEquals("channel_settings",channelBlocked.destination(true,false))
        assertTrue(NotificationAccessStatus(false,true,false).enabled)
    }
}
