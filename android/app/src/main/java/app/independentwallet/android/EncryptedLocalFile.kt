package app.independentwallet.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, bounded device-local metadata; never store unwrapped secrets. */
internal class EncryptedLocalFile(context:Context,name:String,purpose:String,private val maximum:Int) {
    private val file=AtomicFile(File(context.noBackupFilesDir,name))
    private val alias="independent-wallet-$purpose-v1"
    private val aad=alias.toByteArray()
    fun exists()=file.baseFile.exists()||File(file.baseFile.path+".bak").exists()
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        check(create&&!exists()){"Local encryption key is unavailable"}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read():ByteArray? {
        if(!exists())return null
        val bytes=file.openRead().use{it.readNBytesCompat(maximum+33)}
        require(bytes.size in 29..maximum+29&&bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(1,13)));cipher.updateAAD(aad)
        return cipher.doFinal(bytes.copyOfRange(13,bytes.size))
    }
    @Synchronized fun write(bytes:ByteArray) {
        check(!AppWiper.pending(file.baseFile.parentFile!!)){"Wallet erasure is in progress"};require(bytes.size<=maximum)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(aad)
        check(cipher.iv.size==12)
        val out=file.startWrite()
        try{out.write(byteArrayOf(1)+cipher.iv+cipher.doFinal(bytes));file.finishWrite(out)}catch(e:Throwable){file.failWrite(out);throw e}
    }
}
internal fun java.io.InputStream.readNBytesCompat(maximum:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
    while(out.size()<maximum){val n=read(buffer,0,minOf(buffer.size,maximum-out.size()));if(n<0)break;out.write(buffer,0,n)}
    return out.toByteArray()
}
