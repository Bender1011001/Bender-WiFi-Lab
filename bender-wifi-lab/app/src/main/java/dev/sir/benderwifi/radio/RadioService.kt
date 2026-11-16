package dev.sir.benderwifi.radio

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.sir.benderwifi.MainActivity
import dev.sir.benderwifi.R

class RadioService : Service() {
    private var latestStartId=0
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Wi-Fi experiments",NotificationManager.IMPORTANCE_LOW))
        startForeground(ID,notification(this,"Preparing operation"))
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int):Int {
        latestStartId=startId
        if(intent?.action=="stop") RadioController.cancel() else RadioController.executePending {finishOwnedService()}
        if(!RadioController.state.value.busy) finishOwnedService()
        return START_NOT_STICKY
    }
    private fun finishOwnedService() {
        // Main-thread callback tied to this service instance, never stopService()
        // on a component that may already belong to the next session.
        if(stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { RadioController.cancel() }
    override fun onBind(intent: Intent?):IBinder?=null
    companion object {
        internal const val CHANNEL="lab_radio"
        private const val ID=1301
        private fun notification(context:Context,text:String):Notification {
            val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(context,1,Intent(context,RadioService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_bender)
                .setContentTitle("Bender Wi-Fi Lab").setContentText(text.take(160)).setOngoing(true).setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",stop).build()
        }
        fun update(context:Context,text:String) { context.getSystemService(NotificationManager::class.java).notify(ID,notification(context,text)) }
    }
}
