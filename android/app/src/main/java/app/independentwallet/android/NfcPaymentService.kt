package app.independentwallet.android

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.SystemClock

/** In-memory, short-lived public address offer, only while Receive is visible. */
internal object NfcAddressOffer {
    private var payload: ByteArray? = null
    private var deadline = 0L
    @Synchronized fun start(network: String, address: String) {
        payload = NfcPaymentProtocol.encode(network, address)
        deadline = SystemClock.elapsedRealtime() + 60_000
    }
    @Synchronized fun clear() { payload = null; deadline = 0 }
    @Synchronized fun active(): Boolean {
        if (SystemClock.elapsedRealtime() >= deadline) clear()
        return payload != null
    }
    @Synchronized fun consume(): ByteArray? {
        if (!active()) return null
        val result = payload
        clear()
        return result
    }
}

class NfcPaymentService : HostApduService() {
    private var selected = false
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return NfcPaymentProtocol.unavailable
        if (commandApdu.contentEquals(NfcPaymentProtocol.select)) {
            selected = NfcAddressOffer.active()
            return if (selected) NfcPaymentProtocol.ok else NfcPaymentProtocol.unavailable
        }
        if (selected && commandApdu.contentEquals(NfcPaymentProtocol.read)) {
            selected = false
            return NfcAddressOffer.consume()?.plus(NfcPaymentProtocol.ok) ?: NfcPaymentProtocol.unavailable
        }
        selected = false
        return NfcPaymentProtocol.unavailable
    }
    override fun onDeactivated(reason: Int) { selected = false }
}
