package app.independentwallet.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import java.util.UUID

/** Clipboard retention is explicit. Leaving/locking never requests deletion. */
internal class AndroidSeedClipboard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val preferences = context.getSharedPreferences("seed-clipboard-metadata", Context.MODE_PRIVATE)
    private val copy = SeedClipboard(object : SeedClipboardPort {
        override fun writeSensitive(text: String, owner: String) {
            val clip = ClipData.newPlainText("Recovery phrase", text)
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
                putString(OWNER, owner)
            }
            clipboard.setPrimaryClip(clip)
        }
        override fun owner(): String? {
            val description = clipboard.primaryClipDescription ?: return null
            return description.extras?.getString(OWNER) ?: ""
        }
        override fun clear() { clipboard.clearPrimaryClip() }
        override fun savedOwner(): String? = preferences.getString("owner", null)
        override fun rememberOwner(owner: String?) {
            check(preferences.edit().apply { if (owner == null) remove("owner") else putString("owner", owner) }.commit())
        }
    }, { UUID.randomUUID().toString() })
    fun copy(text: String): Boolean = copy.copy(text)
    fun clear() { copy.clearOwned() }
    private companion object { const val OWNER = "app.independentwallet.seed-clipboard-owner" }
}
