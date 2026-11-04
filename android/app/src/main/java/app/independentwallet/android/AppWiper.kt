package app.independentwallet.android

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore

internal interface WipeActions {
    fun markPending()
    fun stopSessions()
    fun deleteKeys()
    fun deleteFiles()
    fun clearApplicationData():Boolean
}
internal fun executeWipe(actions:WipeActions):Boolean {
    try{actions.markPending()}catch(_:Exception){}
    try{actions.stopSessions()}catch(_:Exception){}
    try{actions.deleteKeys()}catch(_:Exception){}
    try{actions.deleteFiles()}catch(_:Exception){}
    return try{actions.clearApplicationData()}catch(_:Exception){false}
}
internal object AppWiper {
    private const val MARKER="wipe-pending-v1"
    fun pending(directory:File)=File(directory,MARKER).exists()
    fun markPending(context:Context) {
        context.noBackupFilesDir.mkdirs();FileOutputStream(File(context.noBackupFilesDir,MARKER)).use{it.write(1);it.fd.sync()}
        val fd=android.system.Os.open(context.noBackupFilesDir.path,android.system.OsConstants.O_RDONLY,0)
        try{android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}
    }
    fun erase(context:Context,stop:()->Unit):Boolean=executeWipe(object:WipeActions {
        override fun markPending()=AppWiper.markPending(context)
        override fun stopSessions(){stop();context.appLocks().lock();context.stopService(android.content.Intent(context,WalletSyncService::class.java))}
        override fun deleteKeys(){
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};var failed=false
            for(alias in keys.aliases().toList().filter{it.startsWith("independent-wallet-")})try{keys.deleteEntry(alias)}catch(_:Exception){failed=true}
            check(!failed)
        }
        override fun deleteFiles(){
            try{AndroidSeedClipboard(context).clear()}catch(_:Exception){}
            for(dir in listOf(context.noBackupFilesDir,context.filesDir,context.cacheDir,context.codeCacheDir))dir.listFiles()?.filterNot{it.name==MARKER}?.forEach{it.deleteRecursively()}
            context.databaseList().forEach{context.deleteDatabase(it)}
        }
        override fun clearApplicationData()=context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    })
}
