package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class InactivityWindowTest {
    @Test fun expiresAtTheDeadlineAndInteractionStartsANewWindow() {
        var time = 0L
        val window = InactivityWindow { time }
        assertFalse(window.expired())
        window.touch(); time = InactivityWindow.LIFETIME_MS - 1; assertFalse(window.expired())
        window.touch(); time += InactivityWindow.LIFETIME_MS - 1; assertFalse(window.expired())
        time++; assertTrue(window.expired())
        window.clear(); assertFalse(window.expired())
    }
    @Test fun aClockRegressionFailsClosed() {
        var time = 100L
        val window = InactivityWindow { time }
        window.touch(); time = 99
        assertTrue(window.expired())
    }
}
