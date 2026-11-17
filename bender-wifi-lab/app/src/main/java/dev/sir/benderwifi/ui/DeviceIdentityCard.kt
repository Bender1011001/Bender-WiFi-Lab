package dev.sir.benderwifi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sir.benderwifi.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun rememberDeviceGuess(mac:String,raw:String):DeviceGuess {
    val context=LocalContext.current.applicationContext
    var index by remember {mutableStateOf<VendorIndex?>(null)}
    LaunchedEffect(Unit){index=withContext(Dispatchers.IO){runCatching {DeviceVendors.load(context)}.getOrNull()}}
    return remember(mac,raw,index){DeviceIdentity.guess(mac,raw,index)}
}

@Composable internal fun DeviceIdentityCard(guess:DeviceGuess) {
    var expanded by remember(guess.hints){mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Text("Best available device guess",fontWeight=FontWeight.Bold)
        Text(guess.title,fontSize=20.sp,color=MaterialTheme.colorScheme.primary)
        Text("${guess.type} · ${guess.confidence}",fontSize=13.sp)
        Text(guess.basis,fontSize=12.sp)
        if(guess.localAddress)Text("Locally administered address · possibly randomized. No factory-vendor match is inferred.",fontSize=12.sp)
        guess.vendor?.let {Text("MAC prefix vendor: ${it.name} (/${it.prefixBits})",fontSize=12.sp)}
        if(guess.hints.isNotEmpty()){
            TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide identity clues" else "Why this guess (${guess.hints.size} clues)")}
            if(expanded)guess.hints.take(10).forEach {hint->
                Text("${DeviceIdentity.reason(hint.kind)}: ${DeviceIdentity.display(hint)}\nCaptured ${stamp(hint.time)} · frame ${hint.frame}"+(hint.session?.let {"\nSource session: $it"}?:""),fontSize=12.sp)
            }
            if(expanded&&guess.hints.size>10)Text("More clues are retained in Raw device evidence.",fontSize=12.sp)
        }
        if(expanded)Text("MAC addresses and advertised fields can be changed or spoofed. Names never merge device records or assign an AP.",fontSize=11.sp)
    }}
}
