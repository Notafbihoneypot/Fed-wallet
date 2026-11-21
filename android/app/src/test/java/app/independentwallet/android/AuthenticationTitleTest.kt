package app.independentwallet.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticationTitleTest {
    @Test fun aPaymentIsNeverAuthorizedUnderAGenericUnlockHeading() {
        assertEquals("Authorize payment", authenticationTitle(VaultAction.Send))
        assertEquals("Authorize retry", authenticationTitle(VaultAction.RetrySend))
        assertEquals("Show transaction key", authenticationTitle(VaultAction.RevealTransactionKey))
        assertEquals("Unlock wallet", authenticationTitle(VaultAction.Unlock))
        // Every action has its own heading except the biometric enrolment, which never shows this dialog.
        VaultAction.entries.filter { it != VaultAction.EnableBiometric }
            .forEach { assert(authenticationTitle(it) != "Authenticate") { "$it has no heading" } }
    }
}
