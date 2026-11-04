package app.independentwallet.android

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** User-started, non-exported scan lifetime. Never opens a wallet/node itself. */
class WalletSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wallet get() = (application as WalletApplication).wallet
    private var collector: Job? = null
    private var connection = -1L
    private var deadline: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { wallet.screenOff() }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wallet sync", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra("connection", -1L) ?: -1L
        if (intent?.action == STOP) {
            wallet.stopBackgroundSync(requested)
            if (requested == connection || collector == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!wallet.syncServiceStarted(requested)) { if (collector == null) stopSelf(startId); return START_NOT_STICKY }
        if (connection != requested) {
            collector?.cancel(); collector = null; deadline?.cancel()
            wake?.let { if (it.isHeld) it.release() }; wake = null
            connection = requested
        }
        val online = wallet.state.value.online
        if (!online.backgroundAllowed || !online.networkEnabled) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE)
            val notification = notification(online)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
            if (collector == null) {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "IndependentWallet:Sync").apply { setReferenceCounted(false) }
                collector = scope.launch {
                    wallet.state.collect { state ->
                        val current = state.online
                        if (!current.backgroundAllowed || !current.networkEnabled || (current.error != null && !(current.paymentError && current.composingPayment))) {
                            wallet.finishBackgroundSync(connection); stopSelf()
                        } else if (!canKeepSyncService(state)) {
                            // Review/authentication keep the service idle; only
                            // revoked permission, locking or pause ends it.
                            stopSelf()
                        } else {
                            // Let the CPU sleep while caught up; wake only for active work.
                            wake?.let { lock ->
                                if (current.busy || current.syncing) { if (!lock.isHeld) lock.acquire(6 * 60 * 60_000L) }
                                else if (lock.isHeld) lock.release()
                            }
                            getSystemService(NotificationManager::class.java).notify(ID, notification(current))
                        }
                    }
                }
                deadline = scope.launch { delay(5 * 60 * 60_000L + 50 * 60_000L); wallet.syncServiceTimedOut(connection); stopSelf() }
            }
        } catch (_: Exception) { wallet.syncServiceFailed(connection); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(online: OnlineUiState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WalletSyncService::class.java).setAction(STOP).putExtra("connection", connection), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val progress = syncNotification(online)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle(progress.title)
            .setContentText(progress.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progress.detail))
            .setSubText(online.route?.connectionLabel)
            .setProgress(progress.max, progress.progress ?: 0, progress.progress == null)
            .setContentIntent(open).addAction(0, "Stop and lock", stop)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { wallet.syncServiceTimedOut(connection); stopSelf() }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        collector?.cancel(); scope.cancel()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        wallet.syncServiceStopped(connection)
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "wallet-sync"; private const val ID = 7; private const val STOP = "app.independentwallet.STOP_SYNC" }
}
