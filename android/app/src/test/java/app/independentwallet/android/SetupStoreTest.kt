package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class SetupStoreTest {
    private class Disk : SetupFile {
        var bytes: ByteArray? = null
        var fail = false
        var commitThenFail = false
        var ignoreDelete = false
        var deleteThenFail = false
        override fun read(): ByteArray? = bytes?.clone()
        override fun write(bytes: ByteArray) { if (fail) error("write failed"); this.bytes = bytes.clone(); if (commitThenFail) error("reply lost") }
        override fun delete() { if (!ignoreDelete) bytes = null; if (deleteThenFail) error("delete reply lost") }
    }
    private class Wrapper : SetupKeyWrapper {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var authenticated = true
        override fun wrap(bytes: ByteArray): ByteArray { check(authenticated); return VaultEnvelope.encrypt(bytes,key) }
        override fun unwrap(bytes: ByteArray): ByteArray { check(authenticated); return VaultEnvelope.decrypt(bytes,key) }
    }
    private val payload = "IWS1 public test checkpoint with a partial backup answer".toByteArray()
    @Test fun savedSetupSurvivesClosingTheLiveLeaseAndRequiresAuthenticationToResume() {
        val disk = Disk(); val wrapper = Wrapper(); val monitor = Any()
        val store = SetupStore(disk,wrapper,monitor)
        val lease = store.create(payload)
        assertFalse(String(disk.bytes!!,Charsets.ISO_8859_1).contains(String(payload)))
        wrapper.authenticated = false
        val next = payload + byteArrayOf(1)
        lease.write(next) // checkpointing an already-unlocked wallet needs no new auth prompt
        lease.close()
        assertThrows(IllegalStateException::class.java) { lease.write(payload) }
        assertThrows(IllegalStateException::class.java) { SetupStore(disk,wrapper,monitor).open() }
        wrapper.authenticated = true
        store.open().use { resumed -> assertArrayEquals(next,resumed.plaintext) }
    }
    @Test fun failedWritesAndStaleSessionsDoNotReplaceDurableProgress() {
        val disk = Disk(); val wrapper = Wrapper(); val store = SetupStore(disk,wrapper,Any())
        val lease = store.create(payload)
        val before = disk.bytes!!.clone(); disk.fail = true
        assertThrows(IllegalStateException::class.java) { lease.write(payload + byteArrayOf(2)) }
        assertArrayEquals(before,disk.bytes)
        disk.fail = false
        store.open().use { stale ->
            lease.write(payload + byteArrayOf(3))
            assertThrows(IllegalStateException::class.java) { stale.lease.write(payload) }
            assertThrows(IllegalStateException::class.java) { stale.lease.discard() }
        }
        assertThrows(IllegalStateException::class.java) { store.create(payload) }
        lease.close()
    }
    @Test fun envelopeTamperingAndTruncationAreRejected() {
        val disk = Disk(); val store = SetupStore(disk,Wrapper(),Any())
        store.create(payload).close()
        val original = disk.bytes!!.clone()
        for (i in listOf(0, 8, 40, 70, original.lastIndex)) {
            val bad = original.clone(); bad[i] = (bad[i].toInt() xor 1).toByte(); disk.bytes = bad
            assertThrows(Exception::class.java) { store.open() }
        }
        disk.bytes = original.copyOf(90)
        assertThrows(IllegalArgumentException::class.java) { store.open() }
    }
    @Test fun uncertainWriteRepliesRecoverTheCommittedProgressWithoutOverwritingIt() {
        val disk = Disk().apply { commitThenFail = true }; val store = SetupStore(disk,Wrapper(),Any())
        assertThrows(IllegalStateException::class.java) { store.create(payload) }
        disk.commitThenFail = false
        store.open().use { restored ->
            assertArrayEquals(payload,restored.plaintext)
            disk.commitThenFail = true
            val next = payload + byteArrayOf(9)
            assertThrows(IllegalStateException::class.java) { restored.lease.write(next) }
            disk.commitThenFail = false
            assertThrows(IllegalStateException::class.java) { restored.lease.write(payload) }
            store.open().use { latest -> assertArrayEquals(next,latest.plaintext) }
        }
    }
    @Test fun backupStepAndPartialAnswersRoundTripWithStrictBounds() {
        val snapshot = ByteArray(48) { (it+1).toByte() }
        SetupRecord(SetupStep.Verify,snapshot,listOf("public", "fixt", "")).use { record ->
            val bytes = record.encode()
            try {
                SetupRecord.decode(bytes).use { decoded ->
                    assertEquals(SetupStep.Verify,decoded.step); assertEquals(listOf("public","fixt",""),decoded.answers)
                    assertArrayEquals(snapshot,decoded.snapshot)
                }
                assertThrows(IllegalArgumentException::class.java) { SetupRecord.decode(bytes + byteArrayOf(0)) }
            } finally { bytes.fill(0) }
        }
        assertTrue(snapshot.all { it == 0.toByte() })
    }
    @Test fun explicitDiscardDoesNotNeedTheSeedOrUnwrappingKeyAndAllowsFreshSetup() {
        val disk = Disk(); val wrapper = Wrapper(); val store = SetupStore(disk,wrapper,Any())
        val original = store.create(payload)
        wrapper.authenticated = false
        // Corrupt ciphertext and an unavailable wrapping key must not trap setup.
        disk.bytes!![disk.bytes!!.lastIndex] = (disk.bytes!!.last().toInt() xor 1).toByte()
        val before = disk.bytes!!.clone()
        val discard = store.prepareDiscard()
        assertArrayEquals(before,disk.bytes) // preparation alone never deletes
        discard.commit()
        assertFalse(store.exists())
        assertThrows(IllegalStateException::class.java) { original.write(payload) }
        original.close()
        wrapper.authenticated = true
        store.create(payload + byteArrayOf(7)).use {
            val replacement = disk.bytes!!.clone()
            assertThrows(IllegalStateException::class.java) { discard.commit() }
            assertArrayEquals(replacement,disk.bytes)
        }
    }
    @Test fun aDiscardConfirmationCannotDeleteAChangedOrReplacedDraft() {
        val disk = Disk(); val store = SetupStore(disk,Wrapper(),Any())
        store.create(payload).use { owner ->
            val discard = store.prepareDiscard()
            owner.write(payload + byteArrayOf(4))
            val changed = disk.bytes!!.clone()
            assertThrows(IllegalStateException::class.java) { discard.commit() }
            assertArrayEquals(changed,disk.bytes)
        }
        val old = store.prepareDiscard()
        store.prepareDiscard().commit()
        store.create(payload + byteArrayOf(5)).close()
        val replacement = disk.bytes!!.clone()
        assertThrows(IllegalStateException::class.java) { old.commit() }
        assertArrayEquals(replacement,disk.bytes)
    }
    @Test fun failedAndUncertainDeletesNeverSilentlyReportASurvivingDraftAsRemoved() {
        val disk = Disk(); val store = SetupStore(disk,Wrapper(),Any())
        store.create(payload).close()
        val discard = store.prepareDiscard(); val before = disk.bytes!!.clone()
        disk.ignoreDelete = true
        assertThrows(IllegalStateException::class.java) { discard.commit() }
        assertArrayEquals(before,disk.bytes)
        disk.ignoreDelete = false; disk.deleteThenFail = true
        assertThrows(IllegalStateException::class.java) { discard.commit() }
        assertFalse(store.exists())
        disk.deleteThenFail = false
        discard.commit() // idempotent recovery of a lost delete reply
        assertFalse(store.exists())
    }
    @Test fun rewrappingKeepsTheExactCheckpointAndRejectsStaleLeases(){
        val disk=Disk();val store=SetupStore(disk,Wrapper(),Any());val lease=store.create(payload)
        val next=payload+byteArrayOf(22);lease.write(next);val before=disk.bytes!!.clone();store.rewrap();val after=disk.bytes!!.clone()
        assertFalse(before.contentEquals(after));assertArrayEquals(before.copyOfRange(68,before.size),after.copyOfRange(68,after.size))
        assertThrows(IllegalStateException::class.java){lease.write(payload)};lease.close();store.open().use{assertArrayEquals(next,it.plaintext)}
    }
    @Test fun interruptedRewrappingKeepsEitherTheOldOrCommittedDraftReadable(){
        val disk=Disk();val store=SetupStore(disk,Wrapper(),Any());store.create(payload).close();val before=disk.bytes!!.clone()
        disk.fail=true;assertThrows(IllegalStateException::class.java){store.rewrap()};assertArrayEquals(before,disk.bytes)
        disk.fail=false;disk.commitThenFail=true;assertThrows(IllegalStateException::class.java){store.rewrap()};disk.commitThenFail=false
        store.open().use{assertArrayEquals(payload,it.plaintext)};store.rewrap();store.open().use{assertArrayEquals(payload,it.plaintext)}
        val tampered=disk.bytes!!.clone();tampered[tampered.lastIndex]=(tampered.last().toInt()xor 1).toByte();disk.bytes=tampered
        assertThrows(Exception::class.java){store.rewrap()};assertArrayEquals(tampered,disk.bytes)
    }

    @Test fun revokedSecurityGrantLeavesSetupUnchanged(){
        val disk=Disk();val store=SetupStore(disk,Wrapper(),Any());store.create(payload).close();val before=disk.bytes!!.clone()
        val authority=SecurityAuthorization{100L};val grant=authority.issue();authority.revoke()
        assertThrows(IllegalStateException::class.java){store.rewrap{work->authority.commit(grant,work)}}
        assertArrayEquals(before,disk.bytes);store.open().use{assertArrayEquals(payload,it.plaintext)}
    }

}
