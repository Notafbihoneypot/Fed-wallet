package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VaultCreationGateTest {
    @Test fun anExistingWalletNeverReachesKeyCreationOrWriting() {
        var called = false
        assertThrows(IllegalStateException::class.java) {
            VaultCreationGate.create({ true }) { called = true }
        }
        assertFalse(called)
    }

    @Test fun separateCallersCannotBothCreateOrOverwriteTheWallet() {
        val saved = AtomicBoolean(false)
        val writes = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                VaultCreationGate.create({ saved.get() }) {
                    firstEntered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    writes.incrementAndGet()
                    saved.set(true)
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = pool.submit {
                secondAttempted.countDown()
                assertThrows(IllegalStateException::class.java) {
                    VaultCreationGate.create({ saved.get() }) {
                        writes.incrementAndGet()
                        saved.set(true)
                    }
                }
            }
            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            assertEquals(1, writes.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun aFailedCreationReleasesTheGateForARetry() {
        assertThrows(java.io.IOException::class.java) {
            VaultCreationGate.create({ false }) { throw java.io.IOException("Public failure fixture") }
        }
        var saved = false
        VaultCreationGate.create({ saved }) { saved = true }
        assertTrue(saved)
    }
}
