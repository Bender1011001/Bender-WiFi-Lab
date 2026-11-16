package dev.sir.benderwifi

import android.app.Application
import dev.sir.benderwifi.radio.RadioController

class LabApplication:Application() {
    override fun onCreate(){super.onCreate();dev.sir.benderwifi.wps.WpsRecovery.init(this);RadioController.init(this)}
}
