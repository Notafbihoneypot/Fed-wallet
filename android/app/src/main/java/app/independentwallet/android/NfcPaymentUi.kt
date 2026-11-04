package app.independentwallet.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.activity()
    else -> null
}

/** Exposes only the selected public address, for one successful tap or 60 seconds. */
@Composable
internal fun NfcReceiveControl(network: String, address: String, enabled: Boolean) {
    val context = LocalContext.current
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val supported = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) }
    var sharing by remember(address, network) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { NfcAddressOffer.clear(); sharing = false }
    DisposableEffect(address, network, sharing, enabled) {
        if (sharing && enabled && adapter?.isEnabled == true && supported) NfcAddressOffer.start(network, address)
        onDispose { NfcAddressOffer.clear() }
    }
    LaunchedEffect(sharing, address, network, enabled) {
        if (sharing) {
            while (enabled && NfcAddressOffer.active()) delay(250)
            NfcAddressOffer.clear(); sharing = false
        }
    }
    if (supported && adapter != null) {
        OutlinedButton(onClick = { if (sharing) NfcAddressOffer.clear(); sharing = !sharing }, enabled = enabled && adapter.isEnabled) {
            Text(if (sharing) "Stop NFC sharing" else "Share address by NFC")
        }
        if (sharing) Text("Hold this unlocked phone against the sender’s phone. Sharing ends after one tap or 60 seconds.", style = MaterialTheme.typography.bodySmall)
        else if (!adapter.isEnabled) Text("Turn on NFC in Android settings to share by tap.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Fed POS exposes a single standard NDEF URI record, not our private wallet AID.
 * Reject extra records and non-URI records so untrusted tag data has one meaning.
 */
private fun readMerchantNdef(tag: Tag, network: String): MoneroPaymentRequest? {
    val ndef = Ndef.get(tag) ?: return null
    try {
        ndef.connect()
        val message = requireNotNull(ndef.ndefMessage) { "The NFC tag has no payment record." }
        require(message.records.size == 1) { "The NFC request contains unexpected records." }
        val record = message.records.single()
        require(record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI)) {
            "The NFC tag is not a payment URI."
        }
        require(record.payload.size <= 2048) { "The NFC payment request is too large." }
        val uri = requireNotNull(record.toUri()) { "The NFC payment URI is invalid." }.toString()
        return parsePosPaymentUri(uri, network)
    } finally {
        try { ndef.close() } catch (_: Exception) {}
    }
}

/** Reader is foreground-only; any NFC request merely fills the Send sheet. */
@Composable
internal fun NfcSendControl(network: String, enabled: Boolean, onRequest: (MoneroPaymentRequest) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var reading by remember { mutableStateOf(false) }
    // Keep HCE phone-to-phone scanning on Android's recommended SKIP_NDEF_CHECK path.
    // Merchant NDEF uses the platform NDEF check instead. Only one reader is active.
    var phoneMode by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var readerCancel by remember { mutableStateOf<AtomicBoolean?>(null) }
    val currentOnRequest by rememberUpdatedState(onRequest)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        readerCancel?.set(false); reading = false
        if (activity != null && adapter != null) try { adapter.disableReaderMode(activity) } catch (_: Exception) {}
    }
    DisposableEffect(reading, phoneMode, activity, adapter, network, enabled) {
        val active = AtomicBoolean(reading && enabled)
        readerCancel = active
        val working = AtomicBoolean(false)
        if (active.get() && activity != null && adapter != null) {
            val handler = Handler(Looper.getMainLooper())
            try {
                adapter.enableReaderMode(activity, { tag ->
                    if (!active.get() || !working.compareAndSet(false, true)) return@enableReaderMode
                    try {
                        val pos = if (phoneMode) null else
                            requireNotNull(readMerchantNdef(tag, network)) { "Expected a merchant NDEF payment tag." }
                        val request = if (pos != null) pos else run {
                            // Preserve the private IWM1 phone-to-phone protocol for existing users.
                            val iso = IsoDep.get(tag) ?: error("NFC device is not compatible")
                            val address = try {
                                iso.connect(); iso.timeout = 3_000
                                check(iso.transceive(NfcPaymentProtocol.select).contentEquals(NfcPaymentProtocol.ok))
                                NfcPaymentProtocol.decode(NfcPaymentProtocol.responseData(iso.transceive(NfcPaymentProtocol.read)), network)
                            } finally { try { iso.close() } catch (_: Exception) {} }
                            MoneroPaymentRequest(address, null)
                        }
                        handler.post {
                            if (active.compareAndSet(true, false)) {
                                currentOnRequest(request)
                                message = if (pos != null) "Merchant payment request read. Confirm the merchant, full address, amount and fee before sending."
                                    else "Address received. Enter an amount and review before sending."
                                reading = false
                            }
                        }
                    } catch (_: Exception) {
                        handler.post { if (active.get()) message = "Could not read a matching wallet request. Try again." }
                    } finally { working.set(false) }
                }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    (if (phoneMode) NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK else 0), null)
            } catch (_: Exception) { message = "NFC reader is unavailable."; reading = false }
        }
        onDispose {
            active.set(false); if (readerCancel === active) readerCancel = null
            if (activity != null && adapter != null) try { adapter.disableReaderMode(activity) } catch (_: Exception) {}
        }
    }
    LaunchedEffect(enabled) { if(!enabled){readerCancel?.set(false);reading=false} }
    LaunchedEffect(reading) { if (reading) { delay(60_000); reading = false; message = "NFC reading stopped." } }
    if (adapter != null && activity != null) {
        if (reading) {
            OutlinedButton(onClick = { reading = false; message = null }, enabled = enabled && adapter.isEnabled) {
                Text("Stop NFC reader")
            }
            Text(if (phoneMode) "Ask the recipient to share their address, then tap phones."
                else "Hold the phone near a Fed POS terminal displaying a payment request.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedButton(onClick = { phoneMode = false; reading = true; message = null }, enabled = enabled && adapter.isEnabled) {
                Text("Tap to pay at POS")
            }
            TextButton(onClick = { phoneMode = true; reading = true; message = null }, enabled = enabled && adapter.isEnabled) {
                Text("Read another wallet by NFC")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!adapter.isEnabled) Text("Turn on NFC in Android settings to read by tap.", style = MaterialTheme.typography.bodySmall)
    }
}
