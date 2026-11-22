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
 * transaction ID or balance. Gadgetbridge forwards them to a PineTime; this app
 * has no Bluetooth code.
 */
internal enum class PaymentAlert(val title: String, val id: Int) {
    Received("Payment received", 7101),
    Confirmed("Payment confirmed", 7102),
}

/**
 * Alerts for activity that appeared between two snapshots of the same wallet.
 * Nothing is reported while catching up (the earlier snapshot was not synced):
 * old payments found during a first sync or rescan must not buzz the watch.
 */
internal fun paymentAlerts(previous: WalletSyncStatus?, next: WalletSyncStatus?): Set<PaymentAlert> {
    if (previous == null || next == null || !previous.synced) return emptySet()
    val alerts = mutableSetOf<PaymentAlert>()
    val known = previous.history.mapTo(HashSet()) { it.transaction }
    if (next.history.any { it.sentXmr == null && it.transaction !in known }) alerts += PaymentAlert.Received
    val pending = previous.payments.filter { it.minedHeight == null }.mapTo(HashSet()) { it.transaction }
    if (next.payments.any { it.transaction in pending && it.minedHeight != null }) alerts += PaymentAlert.Confirmed
    return alerts
}

/** Opt-in, off by default. The preference itself reveals nothing about any wallet. */
internal class PaymentAlertNotifier(private val context: Context) {
    private val preferences = context.getSharedPreferences("payment-alerts-v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    var enabled: Boolean
        get() = preferences.getBoolean(ENABLED, false)
        set(value) { preferences.edit().putBoolean(ENABLED, value).apply() }

    fun post(alerts: Set<PaymentAlert>) {
        if (alerts.isEmpty() || !enabled || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Payment alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Short alerts with no amounts, for your phone and a paired watch"
        })
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
