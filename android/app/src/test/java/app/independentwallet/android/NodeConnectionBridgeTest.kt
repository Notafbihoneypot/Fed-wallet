package app.independentwallet.android

import app.independentwallet.bindings.*
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual native transport/engine/UniFFI errors, using only local fixture sockets. */
class NodeConnectionBridgeTest {
    @Test fun nativeHttpAccessFailureReachesUiAsStatusInsteadOfSize() {
        val detail = failure(false)
        assertTrue(detail.contains("403")); assertEquals("NET_STATUS", nodeFailureCode(detail))
        assertFalse(detail.contains("too large")); assertFalse(detail.contains("private-node-fixture"))
    }
    @Test fun nativeHttpsOnAnHttpPortReachesUiAsTlsInsteadOfSize() {
        val detail = failure(true)
        assertEquals("NET_TLS", nodeFailureCode(detail)); assertFalse(detail.contains("too large"))
        assertFalse(detail.contains("private-node-fixture"))
    }
    @Test fun systemResolvedDomainReachesNativeRpcUsingItsOriginalAuthority() {
        assertEquals("NET_STATUS", nodeFailureCode(failure(false, true)))
    }
    @Test fun systemResolvedHttpsDomainRetainsCertificateAndProtocolChecks() {
        assertEquals("NET_TLS", nodeFailureCode(failure(true, true)))
    }
    private fun failure(tls: Boolean, resolved: Boolean = false): String {
        val directory = Files.createTempDirectory("node-error-bridge").toFile()
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val pool = Executors.newSingleThreadExecutor()
        val server = pool.submit(Callable {
            listener.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                if (tls) assertEquals(22, input.read())
                else {
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read(); check(byte >= 0 && header.length < 8192); header.append(byte.toChar())
                    }
                    val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    check(length in 1..8192); check(input.readNBytes(length).size == length)
                    if (resolved) assertTrue(header.toString().lowercase().contains("host: selected-node.invalid:${listener.localPort}\r\n"))
                }
                val body = "private-node-fixture"
                socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                socket.getOutputStream().flush()
            }
        })
        val wallet = OfflineWalletSession.importSpendKey(KeyNetwork.STAGENET, "01" + "00".repeat(31), 0uL)
        try {
            val endpoint = "${if (tls) "https" else "http"}://${if (resolved) "selected-node.invalid" else "127.0.0.1"}:${listener.localPort}/"
            wallet.openOnline(endpoint, directory.absolutePath, WalletNetworkRoute.DIRECT).use { online ->
                try {
                    val error = assertThrows(OnlineException.Failed::class.java) {
                        if (resolved) online.connectDirectResolved(listOf("127.0.0.1")) else online.connect()
                    }
                    server.get(7, TimeUnit.SECONDS)
                    return error.detail
                } finally { online.disconnect() }
            }
        } finally {
            listener.close(); pool.shutdownNow(); wallet.lock(); wallet.destroy(); directory.deleteRecursively()
        }
    }
}
