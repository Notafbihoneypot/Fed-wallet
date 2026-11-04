package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test

class SetupBridgeTest {
    @Test fun encryptedMidBackupCheckpointRestoresTheSameWalletAndVerificationGate() {
        OfflineWalletSession.generate(KeyNetwork.STAGENET,RecoveryFormat.LEGACY25).use { original ->
            val words = original.recoveryPhrase().split(' ')
            val positions = original.backupChallenge()
            val address = original.details().address
            val plaintext = SetupRecord(SetupStep.Verify,original.snapshotForSetup(),listOf(words[positions[0].toInt()],"","")).use { it.encode() }
            try {
                SetupRecord.decode(plaintext).use { record ->
                    original.lock()
                    OfflineWalletSession.openSetupSnapshot(record.snapshot).use { restored ->
                        assertTrue(restored.details().address == address)
                        assertFalse(restored.details().backupVerified)
                        assertTrue(restored.backupChallenge() == positions)
                        assertTrue(record.answers[0] == words[positions[0].toInt()])
                        assertThrows(WalletException.BackupRequired::class.java) { restored.snapshotForVault() }
                        restored.confirmBackup(positions.map { words[it.toInt()] })
                        assertTrue(restored.details().backupVerified)
                        restored.lock()
                    }
                }
            } finally { plaintext.fill(0) }
        }
    }
    @Test fun setupCheckpointCannotBecomeACompletedWalletByUsingTheOtherConstructor() {
        OfflineWalletSession.generate(KeyNetwork.STAGENET,RecoveryFormat.POLYSEED16).use { original ->
            val bytes = original.snapshotForSetup()
            try {
                assertThrows(WalletException::class.java) { OfflineWalletSession.openSnapshot(bytes) }
                original.lock()
                OfflineWalletSession.openSetupSnapshot(bytes).use { restored ->
                    assertFalse(restored.details().backupVerified)
                    assertThrows(WalletException.BackupRequired::class.java) { restored.snapshotForVault() }
                    restored.lock()
                }
            } finally { bytes.fill(0) }
        }
    }
}
