package app.independentwallet.android

/** Never reads clipboard text. Only a nonsecret ownership token is persisted. */
internal interface SeedClipboardPort {
    fun writeSensitive(text: String, owner: String)
    fun owner(): String?
    fun clear()
    fun savedOwner(): String?
    fun rememberOwner(owner: String?)
}
internal class SeedClipboard(private val port: SeedClipboardPort, private val identifier: () -> String) {
    private var owner: String? = port.savedOwner()
    fun copy(text: String): Boolean {
        if (text.isEmpty() || text.length > 4096) return false
        val token = identifier()
        if (token.isEmpty()) return false
        // Register before IPC: an uncertain reply may still have committed.
        return try {
            port.rememberOwner(token); owner = token
            port.writeSensitive(text, token); true
        } catch (_: RuntimeException) { false }
    }
    fun clearOwned(): Boolean {
        val token = owner ?: return true
        return try {
            val current = port.owner() ?: return false
            if (current == token) port.clear()
            port.rememberOwner(null); owner = null; true
        } catch (_: RuntimeException) { false }
    }
}
