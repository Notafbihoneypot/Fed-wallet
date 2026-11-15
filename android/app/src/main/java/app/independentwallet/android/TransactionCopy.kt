package app.independentwallet.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * Copies a transaction ID or address on an explicit tap. These are already
 * public on-chain or shared by the user. Secrets never use this path: they go
 * through the sensitive, clearable clipboard in the view model.
 */
internal object TransactionCopy {
    fun public(context: Context, label: String, text: String, confirmation: String) {
        if (text.isEmpty()) return
        val copied = try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Exception) { false }
        Toast.makeText(context, if (copied) confirmation else "Clipboard access failed.", Toast.LENGTH_SHORT).show()
    }
}
