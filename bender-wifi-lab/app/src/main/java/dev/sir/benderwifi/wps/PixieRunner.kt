package dev.sir.benderwifi.wps

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/** One isolated native process; no radio, root, shell, network or temporary file. */
object PixieRunner {
    suspend fun solve(binary:File,exchange:PixieExchange,timeoutMs:Long=120_000,onHeartbeat:()->Unit={}):String?=withContext(Dispatchers.IO) {
        require(binary.isFile){"The bundled Pixiewps executable is unavailable on this ABI."}
        // Closing a cancelled pipe can throw InterruptedIOException on Android.
        // Keep that reader failure from replacing the caller's timeout/cancel.
        supervisorScope {
            val process=ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
            val output=async(Dispatchers.IO){process.inputStream.use(PixieProtocol::read)}
            try {
                process.outputStream.bufferedWriter(Charsets.US_ASCII).use {it.write(exchange.stdin())}
                var lastBeat=0L
                val text=withTimeout(timeoutMs) {
                    while(!process.waitFor(100,TimeUnit.MILLISECONDS)){
                        ensureActive()
                        val now=System.nanoTime()
                        if(now-lastBeat>=1_000_000_000L){onHeartbeat();lastBeat=now}
                        yield()
                    }
                    // A pipe reader is part of the deadline too.
                    output.await()
                }
                val pin=PixieProtocol.verifiedPin(exchange,text)
                if(pin==null)require(text.contains("WPS pin not found!")){"Pixiewps did not complete a supported search."}
                pin
            } finally {
                output.cancel()
                process.destroyForcibly()
                runCatching {process.inputStream.close()}
                runCatching {process.waitFor(1,TimeUnit.SECONDS)}
            }
        }
    }
}
