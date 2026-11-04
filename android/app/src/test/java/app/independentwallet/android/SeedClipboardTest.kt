package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class SeedClipboardTest {
    private class Port : SeedClipboardPort {
        var current: String? = ""
        var saved: String? = null
        var clears = 0
        var failReply = false
        override fun writeSensitive(text: String, owner: String) { current = owner; if (failReply) throw IllegalStateException("lost reply") }
        override fun owner(): String? = current
        override fun clear() { clears++; current = "" }
        override fun savedOwner(): String? = saved
        override fun rememberOwner(owner: String?) { saved = owner }
    }
    @Test fun aCopySurvivesControllerRecreationAndClearsOnlyOnRequest() {
        val port = Port()
        assertTrue(SeedClipboard(port) { "fixture-owner" }.copy("public fixture"))
        assertEquals(0, port.clears)
        val restored = SeedClipboard(port) { "new-owner" }
        assertEquals("fixture-owner", port.current)
        assertEquals(0, port.clears)
        assertTrue(restored.clearOwned())
        assertEquals(1, port.clears); assertNull(port.saved)
    }
    @Test fun explicitClearPreservesForeignReplacementAndCanRetryUnavailableMetadata() {
        val port = Port()
        val clipboard = SeedClipboard(port) { "fixture-owner" }
        assertTrue(clipboard.copy("public fixture"))
        port.current = null; assertFalse(clipboard.clearOwned()); assertEquals(0, port.clears)
        port.current = "unrelated replacement"; assertTrue(clipboard.clearOwned())
        assertEquals("unrelated replacement", port.current); assertEquals(0, port.clears)
    }
    @Test fun uncertainWriteReplyKeepsTheCopyAvailableUntilExplicitClear() {
        val port = Port().apply { failReply = true }
        val clipboard = SeedClipboard(port) { "fixture-owner" }
        assertFalse(clipboard.copy("public fixture"))
        assertEquals("fixture-owner", port.current); assertEquals(0, port.clears)
        assertTrue(clipboard.clearOwned()); assertEquals(1, port.clears)
    }
}
