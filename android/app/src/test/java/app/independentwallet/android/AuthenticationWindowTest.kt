package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class AuthenticationWindowTest {
    @Test fun neverValidBeforeARequest() {
        assertFalse(AuthenticationWindow { 100L }.isValid())
    }
    @Test fun boundaryIsStrictAndIndependentOfTimerDelivery() {
        var now = 100L
        val window = AuthenticationWindow { now }
        window.begin()
        now += AuthenticationWindow.LIFETIME_MS - 1
        assertTrue(window.isValid())
        now++
        assertFalse(window.isValid())
        now += 300_000L // The app's timer might not run during this suspension.
        assertFalse(window.isValid())
    }
    @Test fun cancelAndNewRequestHaveSeparateLifetimes() {
        var now = 200L
        val window = AuthenticationWindow { now }
        window.begin()
        window.clear()
        assertFalse(window.isValid())
        now += 100_000
        window.begin()
        assertTrue(window.isValid())
    }
    @Test fun clockReversalFailsClosed() {
        var now = 1000L
        val window = AuthenticationWindow { now }
        window.begin()
        now--
        assertFalse(window.isValid())
    }
}
