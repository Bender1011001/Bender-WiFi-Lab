package dev.sir.benderwifi.wps

import android.content.Context
import dev.sir.benderwifi.data.LabRepository
import kotlinx.coroutines.*

/** Startup reconciliation finishes before a new search can create a running row. */
object WpsRecovery {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var startup:Deferred<Unit>?=null
    @Synchronized fun init(context:Context){
        if(startup==null)startup=scope.async {LabRepository.get(context.applicationContext).reconcileWps()}
    }
    suspend fun await(){checkNotNull(startup){"WPS startup recovery is unavailable"}.await()}
}
