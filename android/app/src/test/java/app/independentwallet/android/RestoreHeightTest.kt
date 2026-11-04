package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class RestoreHeightTest {
    @Test fun exactSupportedBoundariesAreAccepted() {
        assertEquals(0uL, parseRestoreHeight("0"))
        assertEquals(0uL, parseRestoreHeight("0000000000"))
        assertEquals(UInt.MAX_VALUE.toULong(), parseRestoreHeight("4294967295"))
    }

    @Test fun emptyChangedOrAmbiguousInputCannotReachAnImport() {
        for (value in listOf("", "-1", "+1", " 1", "1 ", "1.0", "١", "4294967296", "99999999999999999999", "1\n")) {
            assertNull(parseRestoreHeight(value))
        }
    }
}
