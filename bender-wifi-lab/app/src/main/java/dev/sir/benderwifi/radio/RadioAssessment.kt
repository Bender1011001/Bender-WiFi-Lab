package dev.sir.benderwifi.radio

import org.json.JSONObject

/** A process exit is not proof of radio restoration or a target effect. */
object RadioAssessment {
    private val nonRadioOperations=setOf("setup", "probe", "analyze", "identity", "prepare", "privacy_restore", "privacy_status", "recovery_status", "delete_capture", "cleanup_captures")
    fun rootStateObserved(json:JSONObject):Boolean = json.opt("recovery_pending") is Boolean && json.opt("privacy_pending") is Boolean
    fun managedAndClear(json: JSONObject): Boolean = json.opt("managed_mode") == true && json.opt("recovery_pending") == false
    fun restored(json: JSONObject): Boolean = managedAndClear(json) && json.opt("restoration_verified") == true
    fun recoveryAfter(previous: Boolean, operation: String, supervisorStarted: Boolean, radio: JSONObject): Boolean = when {
        radio.opt("recovery_pending") == true -> true
        restored(radio) -> false
        previous -> true
        operation in nonRadioOperations || !supervisorStarted -> false
        else -> !managedAndClear(radio)
    }
    fun completion(exit: Int, result: JSONObject, recoveryRequired: Boolean, operation: String, intentRecorded:Boolean=false, supervisorStarted:Boolean=false): String = when {
        recoveryRequired && operation !in nonRadioOperations -> "recovery_required"
        result.opt("submission_may_have_occurred") == true -> "uncertain"
        operation=="lab" && (intentRecorded || (supervisorStarted && result.opt("ok") !is Boolean)) && !(exit==0 && result.opt("ok")==true) -> "uncertain"
        exit == 130 -> "cancelled"
        exit != 0 || result.opt("ok") != true -> "failed"
        else -> "completed"
    }
    fun privacyAfter(previous: Boolean, operation: String, supervisorStarted: Boolean, privacy: JSONObject, radio:JSONObject=JSONObject()): Boolean = when {
        // Root recovery survives app-data reset; a positive root journal report
        // must restore the banner even when this session has no privacy report.
        radio.opt("privacy_pending") == true -> true
        privacy.opt("pending") == true -> true
        privacy.opt("pending") == false && (privacy.opt("restored") == true || operation == "privacy_status" || privacy.optString("state") == "idle" || !previous) -> false
        operation in setOf("prepare", "privacy_restore", "capture", "lab", "wps") && supervisorStarted && radio.opt("privacy_pending") !is Boolean -> true
        else -> previous
    }
    fun radioDescription(radio: JSONObject): String = when {
        restored(radio) -> "saved Wi-Fi settings restored; association unchecked"
        managedAndClear(radio) -> "managed mode observed; no recovery pending"
        else -> "radio state unverified"
    }
    fun eligibleOrigin(id: String): Boolean = id.matches(Regex("bw_[0-9]+_[a-f0-9]{12}"))
    fun runtimeAfter(previous:String?,operation:String,outcome:String,report:JSONObject):String? = when {
        report.opt("runtime_ready") is Boolean -> report.toString()
        operation in setOf("setup","probe") && outcome!="completed" -> JSONObject().put("runtime_ready",false).put("message","Runtime check did not complete. Check root access, then check the radio or install the tools again.").toString()
        else -> previous
    }
}
