package dev.sir.benderwifi.ui

/** Main-thread owned. A completed decrypt can outlive cancellation/backgrounding. */
internal class VaultRevealController(private val isForeground:()->Boolean,private val display:(String?)->Unit) {
    private var generation=0L
    fun lock() {generation++;display(null)}
    suspend fun reveal(decrypt:suspend ()->String) {
        if(!isForeground()) return
        val request=++generation
        val secret=decrypt()
        if(request==generation && isForeground()) display(secret)
    }
}
