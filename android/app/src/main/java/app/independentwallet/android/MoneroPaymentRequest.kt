package app.independentwallet.android

import java.math.BigInteger
import java.net.URLDecoder

internal data class MoneroPaymentRequest(val address:String,val amountXmr:String?) {
    override fun toString()="[Monero payment request]"
}

/** Untrusted QR data only fills Send. Native preparation still checks the checksum and network. */
internal fun parseMoneroPaymentRequest(raw:String,network:String):MoneroPaymentRequest {
    val input=raw.trim()
    require(input.length in 95..2048 && input.none{it.isWhitespace() || it.code<32}){"Scan a Monero address or payment QR code."}
    val uri=input.startsWith("monero:",ignoreCase=true)
    val body=if(uri)input.substring(7)else input
    require(!body.contains('#')&&!body.startsWith("//")){"Use a Monero address or payment QR code."}
    val address=body.substringBefore('?')
    val alphabet="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    require(address.length in listOf(95,106)&&address.all{it in alphabet}){"The QR does not contain a Monero receiving address."}
    val prefixes=when(network){"Mainnet"->"48";"Stagenet"->"57";else->error("Unsupported wallet network")}
    require(address.first() in prefixes){"This address is for a different Monero network."}
    if(!body.contains('?'))return MoneroPaymentRequest(address,null)
    require(uri){"Use a Monero payment URI for QR amount requests."}
    val params=linkedMapOf<String,String>()
    val query=body.substringAfter('?')
    require(query.isNotEmpty()){"The payment request has an empty parameter list."}
    for(part in query.split('&')){
        require(part.contains('=')){"The payment request contains an invalid parameter."}
        val key=part.substringBefore('=')
        require(key in setOf("tx_amount","recipient_name","tx_description")){"This payment QR contains unsupported fields. Ask for an address QR instead."}
        require(!params.containsKey(key)){"The payment request repeats a parameter."}
        val value=try{URLDecoder.decode(part.substringAfter('='),"UTF-8")}catch(_:IllegalArgumentException){throw IllegalArgumentException("The payment request has invalid text encoding.")}
        require(value.length<=512&&value.none{it.code<32||it=='\uFFFD'}){"The payment request contains invalid text."}
        params[key]=value
    }
    val amount=params["tx_amount"]?.let{value->
        require(value.length<=32 && value.matches(Regex("[0-9]+(\\.[0-9]{1,12})?"))){"The QR amount must use at most 12 decimal places."}
        val whole=value.substringBefore('.').trimStart('0').ifEmpty{"0"}
        val fraction=if(value.contains('.'))value.substringAfter('.')else ""
        val atomic=BigInteger(whole+fraction.padEnd(12,'0'))
        require(atomic<=BigInteger("18446744073709551615")){"The QR amount is too large."}
        if(atomic.signum()==0)null else whole+fraction.trimEnd('0').let{if(it.isEmpty()) "" else ".$it"}
    }
    return MoneroPaymentRequest(address,amount)
}
