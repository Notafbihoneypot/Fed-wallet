package app.independentwallet.android

import app.independentwallet.bindings.ExportedWalletKeys

data class SecurityStatus(val pin:Boolean=false,val biometrics:Boolean=false,val duress:Boolean=false,val migrating:Boolean=false,val error:String?=null)
/** One sent payment's transaction key, shown only after authentication. Never log. */
class SensitiveTransactionKey(val transaction: String, private var value: String) {
    fun text(): String = value
    fun clear() { value = "" }
    override fun toString(): String = "[transaction key hidden]"
}
class SensitiveKeys(value:ExportedWalletKeys){
    private var values=mapOf("Public spend key" to value.publicSpend,"Public view key" to value.publicView,"Private view key" to value.privateView,"Private spend key" to value.privateSpend.orEmpty())
    fun fields()=values.entries.filter{it.value.isNotEmpty()}.map{it.key to it.value}
    fun text(name:String)=values[name]?.takeIf{it.isNotEmpty()}
    fun clear(){values=emptyMap()}
    override fun toString()="[wallet keys hidden]"
}
