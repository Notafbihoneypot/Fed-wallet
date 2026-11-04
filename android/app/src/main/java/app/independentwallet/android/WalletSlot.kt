package app.independentwallet.android

/** Stable file/key identities. Keep both legacy names and ordinals unchanged. */
class WalletSlot private constructor(val name:String,val label:String,internal val suffix:String,val ordinal:Int) {
    internal val vaultFile get()="wallet$suffix-v1.bin"
    internal val setupFile get()="setup$suffix-v1.bin"
    internal val vaultAlias get()="independent-wallet-authenticated-vault$suffix-v1"
    internal val setupAlias get()="independent-wallet-setup-wrapping$suffix-v1"
    override fun equals(other:Any?)=other is WalletSlot&&name==other.name
    override fun hashCode()=name.hashCode()
    override fun toString()=name
    companion object {
        val Original=WalletSlot("Original","Earlier wallet","",0)
        val Mainnet=WalletSlot("Mainnet","My wallet","-mainnet",1)
        val entries=listOf(Original,Mainnet)
        const val MAX_WALLETS=50
        const val MAX_SLOTS=52
        fun fromId(id:String):WalletSlot=entries.find{it.name==id}?:run{
            require(id.matches(Regex("[0-9a-f]{32}"))){"Invalid wallet identity"}
            WalletSlot(id,"Wallet","-w-$id",-1)
        }
    }
}
