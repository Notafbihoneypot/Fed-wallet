package app.independentwallet.android

/** All activities share one wallet file and Keystore alias in this process.
 * Keep the existence check, key creation and atomic write in one critical section.
 * An instance-level @Synchronized would not coordinate separate vault objects. */
internal object VaultCreationGate {
    private val monitor = Any()

    fun create(exists: () -> Boolean, write: () -> Unit) {
        synchronized(monitor) {
            check(!exists()) { "A wallet is already saved on this phone" }
            write()
        }
    }
}
