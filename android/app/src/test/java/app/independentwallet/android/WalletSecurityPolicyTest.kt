package app.independentwallet.android

import app.independentwallet.bindings.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class WalletSecurityPolicyTest {
    @Test fun walletIdentitiesCannotEscapeTheirStorageNamespace(){
        val a=WalletSlot.fromId("ab".repeat(16));val b=WalletSlot.fromId("cd".repeat(16))
        assertNotEquals(a,b);assertNotEquals(a.vaultFile,b.vaultFile);assertNotEquals(a.vaultAlias,b.vaultAlias)
        assertEquals(a,WalletSlot.fromId(a.name))
        for(id in listOf("../wallet","Original/../Mainnet","a".repeat(33),"AB".repeat(16)))assertThrows(IllegalArgumentException::class.java){WalletSlot.fromId(id)}
        assertFalse(validWalletName("bad\nname"));assertFalse(validWalletName("\u202eHidden"));assertFalse(validWalletName("x".repeat(65)));assertTrue(validWalletName("Savings"))
    }
    @Test fun namedWalletConnectionConsentRoundTripsWithoutCrossGranting(){
        val a=WalletSlot.fromId("ab".repeat(16));val b=WalletSlot.fromId("cd".repeat(16));val node="https://fixture.invalid:18081/"
        val first=RememberedConnection(a,"public-fixture-a",NodeKind.Tor,RememberedConnection.fingerprint(node,NodeKind.Tor),true,true)
        val second=first.copy(slot=b,address="public-fixture-b",reconnect=false)
        val profiles=NodeProfiles().withSavedNode(NodeKind.Tor,node,"Mine").withConnection(first).withConnection(second)
        val result=NodeProfiles.decode(profiles.encode())
        assertEquals(listOf(first,second),result.connections);assertFalse(first.matches(b,first.address,node,NodeKind.Tor))
        assertTrue(result.connections.first{it.slot==a}.reconnect);assertFalse(result.connections.first{it.slot==b}.reconnect)
    }
    @Test fun legacySingleRouteNodesMigrateWhenNamedConsentIsAdded(){
        val url="https://fixture.invalid:18081/";val slot=WalletSlot.fromId("ab".repeat(16))
        val old=NodeProfiles(NodeKind.Direct,url,null)
        val next=old.withConnection(RememberedConnection(slot,"fixture",NodeKind.Direct,RememberedConnection.fingerprint(url,NodeKind.Direct),true,false))
        val result=NodeProfiles.decode(next.encode())
        assertEquals(url,result.active);assertEquals(1,result.savedNodes.size);assertEquals(slot,result.connections.single().slot)
    }
    @Test fun ciphertextRejectsSwappedWalletPurposeKeysAndTampering(){
        val key=SecretKeySpec(ByteArray(32){7},"AES");val plain=ByteArray(32){42};val bytes=PinEnvelope.encrypt(plain,key,"wallet:a")
        assertEquals(64,bytes.size);assertArrayEquals(plain,PinEnvelope.decrypt(bytes,key,"wallet:a"))
        assertThrows(Exception::class.java){PinEnvelope.decrypt(bytes,key,"wallet:b")}
        assertThrows(Exception::class.java){PinEnvelope.decrypt(bytes,SecretKeySpec(ByteArray(32){8},"AES"),"wallet:a")}
        val changed=bytes.clone();changed[40]=(changed[40].toInt()xor 1).toByte();assertThrows(Exception::class.java){PinEnvelope.decrypt(changed,key,"wallet:a")}
    }
    @Test fun pinBoundsAndAttemptDelayAreExplicitAndBounded(){
        assertFalse(validPin(""));assertFalse(validPin("123456"));assertFalse(validPin("1234567x"));assertTrue(validPin("18472639"))
        assertEquals(0L,pinDelayMs(4));assertEquals(30_000L,pinDelayMs(5));assertEquals(60_000L,pinDelayMs(6));assertEquals(3_600_000L,pinDelayMs(Int.MAX_VALUE))
    }
    @Test fun wipeRecordsItsMarkerFirstAndAttemptsAllLaterPhases(){
        for(failure in listOf("none","mark","stop","keys","files","os-clear")){
            val calls=mutableListOf<String>();fun call(name:String){calls+=name;if(name==failure)error("synthetic failure")}
            val result=executeWipe(object:WipeActions{
                override fun markPending()=call("mark")
                override fun stopSessions()=call("stop")
                override fun deleteKeys()=call("keys")
                override fun deleteFiles()=call("files")
                override fun clearApplicationData():Boolean{call("os-clear");return true}
            })
            assertEquals(failure!="os-clear",result);assertEquals(listOf("mark","stop","keys","files","os-clear"),calls)
        }
    }
    @Test fun viewingExportNeverReturnsSpendAuthorityAndLockRevokesExport(){
        val owner=OfflineWalletSession.importSpendKey(KeyNetwork.MAINNET,"01"+"00".repeat(31),0uL)
        try{
            val keys=owner.exportKeys();assertTrue(keys.privateSpend=="01"+"00".repeat(31));assertEquals(64,keys.privateView.length)
            val display=SensitiveKeys(keys);assertEquals("[wallet keys hidden]",display.toString());display.clear();assertTrue(display.fields().isEmpty())
            val watch=OfflineWalletSession.importViewOnly(KeyNetwork.MAINNET,owner.details().address,keys.privateView,0uL)
            try{assertNull(watch.exportKeys().privateSpend);assertTrue(watch.exportKeys().publicSpend==keys.publicSpend)}finally{watch.lock();watch.destroy()}
            owner.lock();assertThrows(WalletException.Locked::class.java){owner.exportKeys()}
        }finally{owner.lock();owner.destroy()}
        val draft=OfflineWalletSession.generate(KeyNetwork.MAINNET,RecoveryFormat.POLYSEED16)
        try{assertThrows(WalletException.BackupRequired::class.java){draft.exportKeys()}}finally{draft.lock();draft.destroy()}
    }
}
