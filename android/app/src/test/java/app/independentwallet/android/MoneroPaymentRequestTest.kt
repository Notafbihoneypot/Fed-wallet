package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class MoneroPaymentRequestTest {
    private val main="4"+"1".repeat(94)
    private val sub="8"+"1".repeat(94)
    @Test fun rawStandardSubaddressAndIntegratedRequests(){
        assertEquals(main,parseMoneroPaymentRequest(main,"Mainnet").address)
        assertEquals(sub,parseMoneroPaymentRequest(sub,"Mainnet").address)
        assertEquals(106,parseMoneroPaymentRequest("4"+"1".repeat(105),"Mainnet").address.length)
        assertNull(parseMoneroPaymentRequest(main,"Mainnet").amountXmr)
        assertEquals("0.000000000001",parseMoneroPaymentRequest("monero:$main?tx_amount=0.000000000001","Mainnet").amountXmr)
    }
    @Test fun uriAmountIsExactAndOptionalMetadataDoesNotChangeDestination(){
        val r=parseMoneroPaymentRequest("monero:$main?tx_amount=0001.230000000000&recipient_name=Test%20recipient&tx_description=Invoice%20A","Mainnet")
        assertEquals(main,r.address);assertEquals("1.23",r.amountXmr)
        assertNull(parseMoneroPaymentRequest("monero:$main?tx_amount=0","Mainnet").amountXmr)
        assertEquals("18446744.073709551615",parseMoneroPaymentRequest("monero:$main?tx_amount=18446744.073709551615","Mainnet").amountXmr)
    }
    @Test fun rejectsAmbiguousUnsafeAndOtherNetworkData(){
        for(raw in listOf("bitcoin:$main","monero://$main","monero:$main#secret","monero:$main?spend_key=secret",
            "monero:$main?tx_payment_id=1234","monero:$main?tx_amount=1&tx_amount=2","monero:$main?tx_amount=1e2",
            "monero:$main?tx_amount=-1","monero:$main?tx_amount=1.0000000000001","monero:$main?tx_amount=18446744.073709551616",
            "monero:$main?recipient_name=%ZZ","monero:$main?recipient_name=%0Asecret","monero:$main?unknown=1")){
            assertThrows(raw,IllegalArgumentException::class.java){parseMoneroPaymentRequest(raw,"Mainnet")}
        }
        assertThrows(IllegalArgumentException::class.java){parseMoneroPaymentRequest(main,"Stagenet")}
        assertThrows(IllegalArgumentException::class.java){parseMoneroPaymentRequest("5"+"1".repeat(94),"Mainnet")}
        assertThrows(IllegalArgumentException::class.java){parseMoneroPaymentRequest("0".repeat(95),"Mainnet")}
        assertThrows(IllegalArgumentException::class.java){parseMoneroPaymentRequest("x".repeat(3000),"Mainnet")}
    }
}
