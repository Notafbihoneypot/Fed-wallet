package app.independentwallet.android

/** Check elapsed time at the result boundary; a suspended app may delay timers. */
internal class AuthenticationWindow(private val elapsedRealtime: () -> Long) {
    private var started: Long? = null
    fun begin() { started = elapsedRealtime() }
    fun clear() { started = null }
    fun isValid(): Boolean {
        val start = started ?: return false
        val now = elapsedRealtime()
        return start >= 0 && now >= start && now - start < LIFETIME_MS
    }
    companion object { const val LIFETIME_MS = 60_000L }
}
