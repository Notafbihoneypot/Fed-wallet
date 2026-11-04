package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class NodeDiagnosticsTest {
    @Test fun diagnosticLoggingNeverCopiesInputOrUnknownCodes() {
        assertEquals("NET_TLS", nodeFailureCode("HTTPS setup failed. [NET_TLS]"))
        assertEquals("NET_PERMISSION", nodeFailureCode("Network access was blocked. [NET_PERMISSION]"))
        for (text in listOf("https://private-fixture.invalid/secret", "[unknown-private-fixture]",
            "seed=private-fixture", "no tag", "[NET_TLS_private-fixture]"))
            assertEquals("NET_UNKNOWN", nodeFailureCode(text))
        assertEquals("NET_STATUS", nodeFailureCode("HTTP 403 with private-fixture [NET_STATUS]"))
    }
}
