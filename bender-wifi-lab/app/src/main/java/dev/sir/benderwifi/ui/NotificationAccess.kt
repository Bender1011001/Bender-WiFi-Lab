package dev.sir.benderwifi.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sir.benderwifi.radio.RadioService

internal data class NotificationAccessStatus(val permissionMissing:Boolean,val appEnabled:Boolean,val channelBlocked:Boolean) {
    val enabled get()=!permissionMissing&&appEnabled&&!channelBlocked
    fun destination(askedBefore:Boolean,showRationale:Boolean):String=when {
        permissionMissing&&(!askedBefore||showRationale)->"permission"
        permissionMissing||!appEnabled->"app_settings"
        channelBlocked->"channel_settings"
        else->"app_settings"
    }
}

/** Optional foreground controls. Denial never starts or cancels a radio job. */
@Composable internal fun NotificationAccessPanel() {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val manager=remember(context){context.getSystemService(NotificationManager::class.java)}
    val preferences=remember(context){context.getSharedPreferences("notification-access",0)}
    fun read()=NotificationAccessStatus(
        Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED,
        manager.areNotificationsEnabled(),
        manager.getNotificationChannel(RadioService.CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE
    )
    var status by remember {mutableStateOf(read())}
    var error by remember {mutableStateOf<String?>(null)}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){status=read()}
    DisposableEffect(lifecycle,context) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME){status=read();error=null}}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    Column {
        TextButton(onClick={
            status=read();error=null
            val activity=context as? Activity
            val rationale=Build.VERSION.SDK_INT>=33&&activity?.let {ActivityCompat.shouldShowRequestPermissionRationale(it,Manifest.permission.POST_NOTIFICATIONS)}==true
            when(status.destination(preferences.getBoolean("asked",false),rationale)) {
                "permission"->if(Build.VERSION.SDK_INT>=33){preferences.edit().putBoolean("asked",true).apply();request.launch(Manifest.permission.POST_NOTIFICATIONS)}
                else->{
                    val channel=status.destination(true,false)=="channel_settings"
                    val intent=Intent(if(channel)Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                    if(channel)intent.putExtra(Settings.EXTRA_CHANNEL_ID,RadioService.CHANNEL)
                    runCatching {context.startActivity(intent)}.onFailure {error="Open Android Settings → Apps → Bender Wi-Fi Lab → Notifications."}
                }
            }
        }){Text(if(status.enabled)"Notification settings" else "Enable notifications")}
        Text(if(status.enabled)"Progress and Cancel are available in notifications." else "Notifications are off. Progress and Cancel remain available inside the app.",fontSize=12.sp)
        error?.let {Text(it,fontSize=12.sp)}
    }
}
