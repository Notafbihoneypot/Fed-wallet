package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Public fixture keys only; never use these accounts for funds. */
class KeystoneBridgeTest {
    private val spend = "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403"
    private val view = "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01"
    @Test fun mainnetPairingPersistsViewingAuthorityAndCannotEnterAnySigningPath() {
        val dir = Files.createTempDirectory("keystone-public-fixture").toFile()
        try {
            OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, spend, 0uL).use { hot ->
                assertTrue(allowedPreviewWallet(hot.details()))
                val address = hot.details().address
                KeystonePairingSession().use { scanner ->
                    val data = """{"version":0,"primaryAddress":"$address","privateViewKey":"$view","restoreHeight":42,"encrypted":false,"source":"Keystone"}"""
                    assertTrue(scanner.receiveFrame(data).complete)
                    scanner.finish(null).use { watching ->
                        assertEquals(KeyWalletMode.KEYSTONE, watching.details().mode)
                        assertTrue(allowedPreviewWallet(watching.details()))
                        assertThrows(WalletException::class.java) { watching.recoveryPhrase() }
                        val bytes = watching.snapshotForVault()
                        try {
                            OfflineWalletSession.openSnapshot(bytes).use { restored ->
                                assertEquals(KeyWalletMode.KEYSTONE, restored.details().mode)
                                assertEquals(42uL, restored.details().restoreHeight)
                                restored.openOnline("https://node.invalid:18081/", dir.absolutePath, WalletNetworkRoute.DIRECT).use { online ->
                                    assertNull(online.status().balanceXmr)
                                    assertNull(online.status().unlockedXmr)
                                    assertThrows(OnlineException::class.java) { online.prepare(address, "1") }
                                    assertThrows(OnlineException::class.java) { online.confirm("forged-review") }
                                    assertThrows(OnlineException::class.java) { online.retryPending("forged-id") }
                                    restored.lock()
                                    assertThrows(OnlineException::class.java) { online.status() }
                                }
                            }
                        } finally { bytes.fill(0) }
                    }
                    assertThrows(WalletException::class.java) { scanner.finish(null) }
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun scannerErrorsDoNotExposeQrContents() {
        KeystonePairingSession().use { scanner ->
            for (value in listOf("ur:xmr-txsigned/private-qr-fixture", """{"encrypted":true,"privateViewKey":"private-qr-fixture"}""", "x".repeat(9001))) {
                val error = assertThrows(WalletException::class.java) { scanner.receiveFrame(value) }
                assertFalse(error.message.orEmpty().contains("private-qr-fixture"))
            }
        }
    }
    @Test fun denseConnectionQrDecodesAtEveryQuarterTurn() {
        OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET, spend, 0uL).use { hot ->
            val data = """{"version":0,"primaryAddress":"${hot.details().address}","privateViewKey":"$view","restoreHeight":0,"encrypted":false,"source":"Keystone"}"""
            val side = 512
            val grid = com.google.zxing.qrcode.QRCodeWriter().encode(data, com.google.zxing.BarcodeFormat.QR_CODE, side, side)
            var pixels = IntArray(side * side) { i -> if (grid[i % side, i / side]) 0xff000000.toInt() else 0xffffffff.toInt() }
            repeat(4) {
                val luma = com.google.zxing.RGBLuminanceSource(side, side, pixels)
                val text = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(luma))).text
                KeystonePairingSession().use { scanner ->
                    assertTrue(scanner.receiveFrame(text).complete)
                    scanner.finish(123uL).use { watching ->
                        assertEquals(123uL, watching.details().restoreHeight)
                        assertEquals(KeyWalletMode.KEYSTONE, watching.details().mode)
                    }
                }
                val previous = pixels
                pixels = IntArray(side * side) { i -> previous[(side - 1 - i % side) * side + i / side] }
            }
        }
    }
}
