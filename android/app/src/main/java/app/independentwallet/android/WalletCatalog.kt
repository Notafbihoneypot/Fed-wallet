package app.independentwallet.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WalletEntry(val id:String,val name:String,val network:String="mainnet",val address:String?=null) {
    val slot get()=WalletSlot.fromId(id)
    val archived get()=network=="stagenet"
    override fun toString()="[wallet entry]"
}
internal fun validWalletName(name:String)=name.isNotBlank()&&name.codePointCount(0,name.length)<=64&&
    name.none{it.isISOControl()||it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'}
internal class WalletCatalog(private val context:Context) {
    private val file=EncryptedLocalFile(context,"wallet-list-v1.bin","wallet-list",65536)
    @Synchronized fun read():List<WalletEntry> {
        val data=file.read()?:return emptyList()
        try{val a=JSONArray(data.toString(Charsets.UTF_8));require(a.length()<=WalletSlot.MAX_SLOTS)
            return List(a.length()){i->val v=a.getJSONObject(i)
                WalletEntry(v.getString("id"),v.getString("name"),v.getString("network"),v.optString("address").ifEmpty{null}).also{
                    it.slot;require(validWalletName(it.name)&&it.network in listOf("mainnet","stagenet","unknown"));require(it.address==null||it.address.length in 90..196)
                }
            }.also{require(it.map{e->e.id}.distinct().size==it.size)}
        }finally{data.fill(0)}
    }
    private fun save(entries:List<WalletEntry>) {
        require(entries.size<=WalletSlot.MAX_SLOTS&&entries.map{it.id}.distinct().size==entries.size)
        val a=JSONArray();entries.forEach{it.slot;require(validWalletName(it.name));a.put(JSONObject().put("id",it.id).put("name",it.name).put("network",it.network).put("address",it.address.orEmpty()))}
        val bytes=a.toString().toByteArray();try{file.write(bytes)}finally{bytes.fill(0)}
    }
    @Synchronized fun initialize():List<WalletEntry> {
        val original=read();var entries=original
        for(slot in WalletSlot.entries)if(entries.none{it.slot==slot}&&(WalletVault(context,slot).exists()||SetupVault(context,slot).store.exists()))
            entries=entries+WalletEntry(slot.name,slot.label,if(slot==WalletSlot.Mainnet)"mainnet"else"unknown")
        val named=Regex("(?:wallet|setup)-w-([0-9a-f]{32})-v1\\.bin(?:\\.bak)?")
        for(path in context.noBackupFilesDir.listFiles().orEmpty()){
            val id=named.matchEntire(path.name)?.groupValues?.get(1)?:continue
            if(entries.none{it.id==id}&&entries.size<WalletSlot.MAX_SLOTS)entries=entries+WalletEntry(id,"Recovered wallet ${entries.size+1}","unknown")
        }
        if(entries!=original)save(entries)
        return entries
    }
    @Synchronized fun add(name:String):WalletEntry {
        val entries=initialize();require(entries.count{!it.archived}<WalletSlot.MAX_WALLETS)
        val next=WalletEntry(UUID.randomUUID().toString().replace("-",""),name.trim().ifEmpty{"Wallet ${entries.count{!it.archived}+1}"})
        require(validWalletName(next.name));save(entries+next);return next
    }
    @Synchronized fun identify(slot:WalletSlot,address:String,mainnet:Boolean) {
        val entries=initialize();val old=entries.find{it.slot==slot}?:WalletEntry(slot.name,slot.label)
        save(entries.filterNot{it.slot==slot}+old.copy(address=address,network=if(mainnet)"mainnet"else"stagenet"))
    }
    @Synchronized fun rename(slot:WalletSlot,name:String){require(validWalletName(name));save(initialize().map{if(it.slot==slot)it.copy(name=name.trim())else it})}
}
