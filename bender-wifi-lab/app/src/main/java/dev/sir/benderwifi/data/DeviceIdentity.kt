package dev.sir.benderwifi.data

import android.content.Context
import java.io.Reader

data class VendorMatch(val name:String,val prefixBits:Int)

/** Prefix assignee only. Never clear the local bit to manufacture a vendor match. */
class VendorIndex private constructor(private val prefixes:Map<Int,Map<Long,String>>) {
    fun lookup(address:String):VendorMatch? {
        val value=macValue(address)?:return null
        if(value==0L || ((value ushr 40) and 3L)!=0L)return null
        return prefixes.keys.sortedDescending().firstNotNullOfOrNull {bits->prefixes.getValue(bits)[value ushr (48-bits)]?.let {VendorMatch(it,bits)}}
    }
    companion object {
        private val address=Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        fun macValue(mac:String):Long?=if(address.matches(mac))mac.replace(":","").toLongOrNull(16) else null
        fun locallyAdministered(mac:String):Boolean=macValue(mac)?.let {((it ushr 40) and 2L)!=0L}?:false
        fun read(reader:Reader):VendorIndex {
            val maps=mutableMapOf<Int,MutableMap<Long,String>>()
            reader.buffered().useLines {lines->lines.forEach {line->
                val parts=line.split('\t',limit=3)
                require(parts.size==3){"Invalid manufacturer table"}
                val bits=parts[0].toInt();val key=parts[1].toLong(16)
                require(bits in 24..48 && key in 0L until (1L shl bits) && IdentityHints.readable(parts[2],200))
                maps.getOrPut(bits){mutableMapOf()}[key]=parts[2]
            }}
            return VendorIndex(maps)
        }
    }
}

object DeviceVendors {
    @Volatile private var cached:VendorIndex?=null
    /** Call off the UI thread. No network requests or third-party identification service. */
    fun load(context:Context):VendorIndex=cached?:synchronized(this){cached?:context.assets.open("runtime/device-vendors.tsv").reader(Charsets.UTF_8).use(VendorIndex::read).also {cached=it}}
}

data class DeviceGuess(val title:String,val type:String,val confidence:String,val basis:String,
    val vendor:VendorMatch?,val localAddress:Boolean,val hints:List<IdentityHint>,val conflict:Boolean=false)

object DeviceIdentity {
    private val categories=mapOf(1 to "Computer",2 to "Input device",3 to "Printer / scanner",4 to "Camera",5 to "Storage device",6 to "Network equipment",7 to "TV / display",8 to "Media device",9 to "Game console",10 to "Phone",11 to "Audio device")
    private val services=mapOf("_googlecast._tcp.local" to "Cast / media device", "_airplay._tcp.local" to "AirPlay receiver",
        "_raop._tcp.local" to "Audio receiver", "_ipp._tcp.local" to "Printer / print server",
        "_ipps._tcp.local" to "Printer / print server", "_printer._tcp.local" to "Printer / print server",
        "_hap._tcp.local" to "HomeKit accessory / bridge", "_matter._tcp.local" to "Matter device",
        "_matterc._udp.local" to "Matter device")
    private val generic=setOf("unknown","unspecified","none","n/a","device","model","0","1")
    private fun meaningful(value:String)=value.trim().lowercase() !in generic
    fun reason(kind:String):String=when(kind){
        "wps_name"->"WPS device name";"wps_manufacturer"->"WPS manufacturer";"wps_model"->"WPS model"
        "wps_model_number"->"WPS model number";"wps_type"->"WPS device type"
        "dhcp_hostname"->"DHCP host name";"dhcp_vendor"->"DHCP software identifier"
        "mdns_service"->"mDNS advertised service";else->"Captured clue"
    }
    fun display(hint:IdentityHint):String=if(hint.kind=="wps_type")categories[hint.value.take(4).toIntOrNull(16)]?:"Unrecognized WPS type" else hint.value
    fun guess(mac:String,raw:String,index:VendorIndex?):DeviceGuess {
        val vendor=index?.lookup(mac)
        val local=VendorIndex.locallyAdministered(mac)
        val hints=IdentityHints.fromJson(raw)
        fun first(kind:String)=hints.firstOrNull {it.kind==kind&&meaningful(it.value)}?.value
        val model=first("wps_model")
        val manufacturer=first("wps_manufacturer")
        val name=first("wps_name")?:first("dhcp_hostname")
        val modelValues=hints.filter {it.kind=="wps_model"&&meaningful(it.value)}.map {it.value.lowercase()}.distinct()
        val types=hints.filter {it.kind=="wps_type"}.mapNotNull {categories[it.value.take(4).toIntOrNull(16)]}.distinct()
        val serviceTypes=hints.filter {it.kind=="mdns_service"}.mapNotNull {services[it.value]}.distinct()
        val conflict=modelValues.size>1 || types.size>1
        if(conflict)return DeviceGuess("Device identity unclear","Type unclear","Conflicting clues","Different models or device types were advertised by this address; inspect the evidence.",vendor,local,hints,true)
        val software=first("dhcp_vendor")?.lowercase().orEmpty()
        val softwareType=when {software.startsWith("android-dhcp")->"Android-based device";software.startsWith("msft 5.")->"Windows-family device";else->null}
        val hardwareType=vendor?.name?.lowercase()?.let {when {
            it.contains("roku")->"TV / streamer";it.contains("espressif")->"Embedded Wi-Fi / IoT hardware"
            it.contains("signify")->"Lighting / bridge";it.contains("tuya")->"Smart-home hardware";else->null
        }}
        val inferred=serviceTypes.singleOrNull()?:softwareType?:hardwareType
        val type=types.singleOrNull()?:inferred?.let {"$it (possible)"}?:"Type unknown"
        val title=when {
            model!=null -> listOfNotNull(manufacturer?.takeUnless {model.contains(it,true)},model).joinToString(" ")
            name!=null -> name
            manufacturer!=null -> "$manufacturer device"
            types.isNotEmpty() -> types.single()
            serviceTypes.size==1 -> serviceTypes.single()
            softwareType!=null -> softwareType
            vendor!=null -> vendor.name
            local -> "Private / locally administered device"
            else -> "Unidentified device"
        }
        val reported=model!=null||manufacturer!=null||name!=null||types.isNotEmpty()
        val confidence=if(reported)"Device-reported" else if(vendor!=null||serviceTypes.isNotEmpty()||softwareType!=null)"Possible" else "Unknown"
        val basis=when {
            reported -> "Name, model or type supplied by this address; not independently verified."
            serviceTypes.isNotEmpty() -> "Advertised services are clues; a device may proxy services for other hosts."
            softwareType!=null -> "DHCP identifies a software family, not a specific product model."
            vendor!=null -> "MAC prefix identifies the registered hardware vendor; the retail brand or model may differ."
            local -> "Locally administered MAC: it may be randomized. Manufacturer lookup would be misleading."
            else -> "No usable identity clues captured yet."
        }
        return DeviceGuess(title,type,confidence,basis,vendor,local,hints)
    }
}
