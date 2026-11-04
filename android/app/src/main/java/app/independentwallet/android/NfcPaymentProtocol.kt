package app.independentwallet.android

/** Private, versioned phone-to-phone request. This is not a Goblin or bank-card protocol. */
internal object NfcPaymentProtocol {
    val aid = byteArrayOf(0xF0.toByte(), 0x49, 0x57, 0x4D, 0x58, 0x4D, 0x52, 0x01)
    val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
    val read = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x00)
    val ok = byteArrayOf(0x90.toByte(), 0x00)
    val unavailable = byteArrayOf(0x69, 0x85.toByte())
    private const val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private fun validAddress(address: String) = address.length in listOf(95, 106) && address.all { it in alphabet }

    fun encode(network: String, address: String): ByteArray {
        require(network in listOf("Mainnet", "Stagenet") && validAddress(address))
        // Catch wrong-network prefixes before the address reaches the Send sheet.
        parseMoneroPaymentRequest(address, network)
        return "IWM1|$network|$address".toByteArray(Charsets.US_ASCII)
    }
    fun decode(bytes: ByteArray, network: String): String {
        require(bytes.size in 108..125 && bytes.all { it.toInt() in 33..126 })
        val parts = bytes.toString(Charsets.US_ASCII).split('|')
        require(parts.size == 3 && parts[0] == "IWM1" && parts[1] == network && validAddress(parts[2]))
        parseMoneroPaymentRequest(parts[2], network)
        return parts[2]
    }
    fun responseData(response: ByteArray): ByteArray {
        require(response.size in 2..127 && response.takeLast(2).toByteArray().contentEquals(ok))
        return response.copyOf(response.size - 2)
    }
}
