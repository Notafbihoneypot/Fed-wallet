package app.independentwallet.android

import app.independentwallet.bindings.ExportedWalletKeys

data class SecurityStatus(val pin:Boolean=false,val biometrics:Boolean=false,val duress:Boolean=false,val migrating:Boolean=false,val error:String?=null)
class SensitiveKeys(value:ExportedWalletKeys){
    private var values=mapOf("Public spend key" to value.publicSpend,"Public view key" to value.publicView,"Private view key" to value.privateView,"Private spend key" to value.privateSpend.orEmpty())
    fun fields()=values.entries.filter{it.value.isNotEmpty()}.map{it.key to it.value}
    fun text(name:String)=values[name]?.takeIf{it.isNotEmpty()}
    fun clear(){values=emptyMap()}
    override fun toString()="[wallet keys hidden]"
}
