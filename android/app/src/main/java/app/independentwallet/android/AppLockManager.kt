package app.independentwallet.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.independentwallet.bindings.derivePinKey
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal sealed class PinCheck {
    class Unlocked(val root:ByteArray):PinCheck(),AutoCloseable {
        override fun close(){root.fill(0)}
        override fun toString()="[authenticated key]"
    }
    data object Duress:PinCheck()
    data class Rejected(val message:String):PinCheck()
}
internal fun validPin(pin:String)=pin.length in 8..12&&pin.all{it in '0'..'9'}
internal fun pinDelayMs(failures:Int):Long=if(failures<5)0 else minOf(3_600_000L,30_000L shl (failures-5).coerceIn(0,7))

/** No plaintext PIN/root is persisted. Security commits are fenced against lock. */
internal class AppLockManager(private val context:Context) {
    private val file=EncryptedLocalFile(context,"app-lock-v1.bin","app-lock-config",8192)
    private val pinMonitor=Any()
    private val rootMonitor=SecurityAuthorization(SystemClock::elapsedRealtime)
    private var activeRoot:ByteArray?=null
    private fun config():JSONObject?=file.read()?.let{bytes->try{
        JSONObject(bytes.toString(Charsets.UTF_8)).also{require(it.getInt("version")==1&&decode(it,"salt").size==16&&decode(it,"normal").size==64)}
    }finally{bytes.fill(0)}}
    private fun save(cfg:JSONObject){val bytes=cfg.toString().toByteArray();try{file.write(bytes)}finally{bytes.fill(0)}}
    fun configured()=file.exists()
    fun biometricEnabled()=config()?.has("bio")==true
    fun duressEnabled()=config()?.has("duress")==true
    fun migrating()=config()?.optBoolean("migrating",false)==true
    fun lock()=synchronized(rootMonitor){rootMonitor.revoke();activeRoot?.fill(0);activeRoot=null}
    fun activate(root:ByteArray)=synchronized(rootMonitor){require(root.size==32);rootMonitor.revoke();activeRoot?.fill(0);activeRoot=root.copyOf()}
    fun securityGrant()=rootMonitor.issue()
    fun grantCurrent(grant:SecurityAuthorization.Grant)=rootMonitor.current(grant)
    private fun rootCopy():ByteArray=synchronized(rootMonitor){checkNotNull(activeRoot){"Unlock with your app PIN"}.copyOf()}
    fun walletKey(slot:WalletSlot,purpose:String):SecretKey {
        val root=rootCopy()
        try{val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(root,"HmacSHA256"))
            val key=mac.doFinal("independent-wallet-vault-v2:$purpose:${slot.name}".toByteArray())
            return try{SecretKeySpec(key,"AES")}finally{key.fill(0)}
        }finally{root.fill(0)}
    }
    private fun hmacKey(create:Boolean):SecretKey {
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (keys.getKey(HMAC_ALIAS,null)as?SecretKey)?.let{return it}
        check(create&&!configured()){"Device PIN wrapping key is unavailable"}
        return KeyGenerator.getInstance("HmacSHA256","AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(HMAC_ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).setUnlockedDeviceRequired(true).build())
        }.generateKey()
    }
    private fun deviceTag(derived:ByteArray,purpose:String,create:Boolean=false):ByteArray {
        val mac=Mac.getInstance("HmacSHA256");mac.init(hmacKey(create));mac.update(purpose.toByteArray());return mac.doFinal(derived)
    }
    private fun derive(pin:String,salt:ByteArray):ByteArray {
        require(validPin(pin)){"Use an 8–12 digit PIN"};val input=pin.toByteArray(Charsets.US_ASCII)
        return try{derivePinKey(input,salt)}finally{input.fill(0)}
    }
    fun checkPin(pin:String):PinCheck=synchronized(pinMonitor){
        val cfg=checkNotNull(config()){"Set an app PIN first"}
        if(!validPin(pin))return@synchronized PinCheck.Rejected("Enter your 8–12 digit PIN")
        val derived=derive(pin,decode(cfg,"salt"))
        try{
            val tag=deviceTag(derived,"duress-v1")
            val duress=try{cfg.has("duress")&&MessageDigest.isEqual(tag,decode(cfg,"duress"))}finally{tag.fill(0)}
            if(duress)return@synchronized PinCheck.Duress
            val failures=cfg.optInt("failures",0).coerceIn(0,1000);val now=SystemClock.elapsedRealtime()
            val boot=Settings.Global.getInt(context.contentResolver,"boot_count",-1)
            if(failures>=5&&(cfg.optInt("boot",-2)!=boot||now<cfg.optLong("last",0))){
                cfg.put("boot",boot).put("last",now).put("until",now+pinDelayMs(failures));save(cfg)
            }
            val remaining=(cfg.optLong("until",0)-now).coerceAtLeast(0)
            if(remaining>0)return@synchronized PinCheck.Rejected("Try again in ${(remaining+999)/1000} seconds")
            val key=deviceTag(derived,"normal-v1")
            val root=try{PinEnvelope.decrypt(decode(cfg,"normal"),SecretKeySpec(key,"AES"),"app-root-v1")}catch(_:Exception){null}finally{key.fill(0)}
            if(root==null){
                val next=(failures+1).coerceAtMost(1000);cfg.put("failures",next).put("boot",boot).put("last",now).put("until",now+pinDelayMs(next));save(cfg)
                PinCheck.Rejected("Incorrect PIN")
            }else try{require(root.size==32);cfg.put("failures",0).put("until",0);save(cfg);PinCheck.Unlocked(root)}
                catch(error:Throwable){root.fill(0);throw error}
        }finally{derived.fill(0)}
    }
    fun setPin(pin:String,grant:SecurityAuthorization.Grant)=synchronized(pinMonitor){
        rootMonitor.commit(grant){};require(validPin(pin)){"Use an 8–12 digit PIN"}
        val old=config();val salt=old?.let{decode(it,"salt")}?:ByteArray(16).also{SecureRandom().nextBytes(it)}
        if(old==null)check(context.noBackupFilesDir.listFiles().orEmpty().none{path->
            val name=path.name.removeSuffix(".bak").removeSuffix(".new")
            if(!path.isFile||!(name.startsWith("wallet")||name.startsWith("setup"))||!name.endsWith("-v1.bin"))false
            else path.inputStream().use{val head=it.readNBytesCompat(8);PinEnvelope.isPin(head)||(head.size==8&&PinEnvelope.isPin(head.copyOfRange(4,8)))}
        }){"The previous PIN configuration is unavailable. Existing encrypted wallets were kept."}
        val root=rootMonitor.commit(grant){if(old==null)ByteArray(32).also{SecureRandom().nextBytes(it)}else rootCopy()}
        try{val derived=derive(pin,salt)
            try{
                if(old?.has("duress")==true){val tag=deviceTag(derived,"duress-v1");try{require(!MessageDigest.isEqual(tag,decode(old,"duress"))){"Choose a PIN different from the duress PIN"}}finally{tag.fill(0)}}
                val key=deviceTag(derived,"normal-v1",old==null)
                val wrapped=try{PinEnvelope.encrypt(root,SecretKeySpec(key,"AES"),"app-root-v1")}finally{key.fill(0)}
                val cfg=old?:JSONObject().put("version",1).put("salt",encode(salt)).put("migrating",true)
                cfg.put("normal",encode(wrapped)).put("failures",0).put("until",0)
                rootMonitor.commit(grant){save(cfg);activeRoot?.fill(0);activeRoot=root.copyOf()}
            }finally{derived.fill(0)}
        }finally{root.fill(0)}
    }
    fun setDuress(pin:String,grant:SecurityAuthorization.Grant)=synchronized(pinMonitor){
        rootMonitor.commit(grant){check(activeRoot!=null)}
        val cfg=checkNotNull(config());check(!cfg.optBoolean("migrating"));val derived=derive(pin,decode(cfg,"salt"))
        try{
            val key=deviceTag(derived,"normal-v1")
            val same=try{val root=PinEnvelope.decrypt(decode(cfg,"normal"),SecretKeySpec(key,"AES"),"app-root-v1");root.fill(0);true}catch(_:Exception){false}finally{key.fill(0)}
            require(!same){"Choose a duress PIN different from your normal PIN"}
            val tag=deviceTag(derived,"duress-v1");try{rootMonitor.commit(grant){cfg.put("duress",encode(tag));save(cfg)}}finally{tag.fill(0)}
        }finally{derived.fill(0)}
    }
    fun disableDuress(grant:SecurityAuthorization.Grant)=synchronized(pinMonitor){rootMonitor.commit(grant){val cfg=checkNotNull(config());cfg.remove("duress");save(cfg)}}
    fun finishMigration(slots:List<WalletSlot>,grant:SecurityAuthorization.Grant)=synchronized(pinMonitor){
        rootMonitor.commit(grant){};if(!migrating())return@synchronized
        // Acquire each file's own monitor before the revocation monitor: setup
        // operations use that order too. Each conversion and readback is fenced.
        for(slot in slots.distinct()){
            WalletVault(context,slot).migrateToPin { work -> rootMonitor.commit(grant, work) }
            SetupVault(context,slot).store.rewrap { work -> rootMonitor.commit(grant, work) }
        }
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        rootMonitor.commit(grant){
            for(slot in slots.distinct()){keys.deleteEntry(slot.vaultAlias);keys.deleteEntry(slot.setupAlias)}
            val cfg=checkNotNull(config());cfg.put("migrating",false);save(cfg)
        }
    }
    fun biometricCipher(enrolling:Boolean,grant:SecurityAuthorization.Grant?=null):Cipher = synchronized(pinMonitor) {
        val prepare = {
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};var key=if(enrolling)null else keys.getKey(BIO_ALIAS,null)as?SecretKey
        if(enrolling){
            // No saved biometric wrapper may be replaced here. A canceled
            // enrollment can leave an unusable alias behind.
            check(config()?.has("bio")!=true){"Disable biometric unlock before enabling it again"}
            keys.deleteEntry(BIO_ALIAS)
            val spec=KeyGenParameterSpec.Builder(BIO_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true).setUnlockedDeviceRequired(true)
            if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
            else{@Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1)}
            key=KeyGenerator.getInstance("AES","AndroidKeyStore").apply{init(spec.build())}.generateKey()
        }
        checkNotNull(key){"Use your app PIN"}
        Cipher.getInstance("AES/GCM/NoPadding").apply{
            if(enrolling)init(Cipher.ENCRYPT_MODE,key)else{val bio=decode(checkNotNull(config()),"bio");require(bio.size==60);init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bio.copyOfRange(0,12)))}
        }
        }
        if(enrolling)rootMonitor.commit(checkNotNull(grant)){
            check(activeRoot!=null && !migrating());prepare()
        }else prepare()
    }
    fun completeBiometric(cipher:Cipher,enrolling:Boolean,grant:SecurityAuthorization.Grant?):ByteArray?=synchronized(pinMonitor){
        val cfg=checkNotNull(config())
        if(enrolling){rootMonitor.commit(checkNotNull(grant)){
            check(!cfg.optBoolean("migrating"));check(cipher.iv.size==12);val root=rootCopy()
            // Updating AAD is a Keystore operation too. Auth-per-use keys must
            // not receive it until BiometricPrompt authorizes this cipher.
            try{cipher.updateAAD(BIO_AAD);cfg.put("bio",encode(cipher.iv+cipher.doFinal(root)));save(cfg)}finally{root.fill(0)}
        };null}else{val bio=decode(cfg,"bio");require(bio.size==60);cipher.updateAAD(BIO_AAD);cipher.doFinal(bio.copyOfRange(12,bio.size)).also{require(it.size==32)}}
    }
    fun disableBiometrics(grant:SecurityAuthorization.Grant)=synchronized(pinMonitor){rootMonitor.commit(grant){
        val cfg=checkNotNull(config());cfg.remove("bio");save(cfg);KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(BIO_ALIAS)}
    }}
    companion object {
        const val HMAC_ALIAS="independent-wallet-app-pin-device-v1"
        const val BIO_ALIAS="independent-wallet-app-biometric-v1"
        private val BIO_AAD="independent-wallet-biometric-root-v1".toByteArray()
        private fun encode(bytes:ByteArray)=Base64.encodeToString(bytes,Base64.NO_WRAP)
        private fun decode(cfg:JSONObject,name:String)=Base64.decode(cfg.getString(name),Base64.NO_WRAP)
    }
}
internal fun Context.appLocks():AppLockManager=(applicationContext as WalletApplication).locks
