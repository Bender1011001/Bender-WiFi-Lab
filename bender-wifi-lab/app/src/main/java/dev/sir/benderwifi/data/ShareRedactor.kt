package dev.sir.benderwifi.data

import org.json.JSONObject

/** Only typed, explicitly allowed metadata can cross the sharing boundary. */
internal class ShareRedactor {
    fun clean(root:JSONObject):JSONObject=ObservationSchema(sharing=true).clean(root)
}
