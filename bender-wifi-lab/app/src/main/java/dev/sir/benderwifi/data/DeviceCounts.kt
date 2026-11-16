package dev.sir.benderwifi.data

import org.json.JSONObject

/** An AP's saved, time-scoped BSS Load report, separate from identified MACs. */
internal data class AdvertisedStationCount(val count:Int,val time:Double)

internal fun NetworkEntity.advertisedStationCount():AdvertisedStationCount?=runCatching {
    val report=JSONObject(rawJson).optJSONObject("bss_load")?:return@runCatching null
    val count=(report.opt("station_count") as? Number)?.toDouble()?:return@runCatching null
    val time=(report.opt("time") as? Number)?.toDouble()?:return@runCatching null
    if(!count.isFinite()||count !in 0.0..65535.0||count!=count.toInt().toDouble()||!time.isFinite()||time<0)return@runCatching null
    AdvertisedStationCount(count.toInt(),time)
}.getOrNull()
