package app.independentwallet.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.independentwallet.bindings.WalletSyncStatus

/**
 * Watch alerts (owner decisions): fixed wording only, never an amount, address,
 * transaction ID or balance. The watch's companion app forwards them (Gadgetbridge
 * for PineTime, Galaxy Wearable for Samsung); this app has no Bluetooth code.
 */
internal enum class PaymentAlert(val title: String, val id: Int) {
    Received("Payment received", 7101),
    Confirmed("Payment confirmed", 7102),
}

/**
 * Turns successive status snapshots of one wallet into alerts.
 *
 * "Received" fires only for a new incoming transfer in a block at or above the
 * tip the wallet had reached when it was last fully synced, so payments found
 * while catching up (first sync, reopening, rescan) never buzz the watch.
 * The synced flag alone cannot be used: it turns false whenever a new block is
 * seen, which is exactly when a new payment arrives.
 */
internal class PaymentAlertTracker {
    private var previous: WalletSyncStatus? = null
    /** First block not yet scanned when the wallet was last fully synced. */
    private var syncedTip: ULong? = null

    fun reset() { previous = null; syncedTip = null }

    fun next(status: WalletSyncStatus?): Set<PaymentAlert> {
        if (status == null) { reset(); return emptySet() }
        val before = previous
        val tip = syncedTip
        val alerts = mutableSetOf<PaymentAlert>()
        if (before != null && tip != null) {
            val known = before.history.mapTo(HashSet()) { it.transaction }
            if (status.history.any { it.sentXmr == null && it.transaction !in known && it.height >= tip })
                alerts += PaymentAlert.Received
            val pending = before.payments.filter { it.minedHeight == null }.mapTo(HashSet()) { it.transaction }
            if (status.payments.any { it.transaction in pending && it.minedHeight != null }) alerts += PaymentAlert.Confirmed
        }
        if (status.synced) syncedTip = maxOf(syncedTip ?: 0uL, status.nextHeight)
        previous = status
        return alerts
    }
}

/** Opt-in, off by default. The preference itself reveals nothing about any wallet. */
internal class PaymentAlertNotifier(private val context: Context) {
    private val preferences = context.getSharedPreferences("payment-alerts-v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    var enabled: Boolean
        get() = preferences.getBoolean(ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(ENABLED, value).apply()
            // Created as soon as alerts are switched on, so the category shows
            // in Android's settings (and companion apps) before the first alert.
            if (value) createChannel()
        }

    private fun createChannel() = manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Payment alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Short alerts with no amounts, for your phone and a paired watch"
        })

    fun post(alerts: Set<PaymentAlert>) {
        if (alerts.isEmpty() || !enabled || !manager.areNotificationsEnabled()) return
        createChannel()
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        alerts.forEach { alert ->
            try {
                manager.notify(alert.id, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_wallet)
                    .setContentTitle(alert.title).setContentText(ALERT_TEXT).setContentIntent(open).setAutoCancel(true)
                    // Hidden on the lock screen; notification listeners such as Gadgetbridge still receive it.
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET).build())
            } catch (_: SecurityException) { /* Notification permission revoked: alerts are optional. */ }
        }
    }

    private companion object {
        const val CHANNEL = "payment-alerts"
        const val ENABLED = "enabled"
    }
}

internal const val ALERT_TEXT = "Open VoidKey for details."
