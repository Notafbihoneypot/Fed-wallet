package app.independentwallet.android

internal class InactivityWindow(private val now: () -> Long) {
    private var last: Long? = null
    fun touch() { last = now() }
    fun clear() { last = null }
    fun expired(): Boolean {
        val previous = last ?: return false
        val current = now()
        return current < previous || current - previous >= LIFETIME_MS
    }
    companion object { const val LIFETIME_MS = 5 * 60_000L }
}
