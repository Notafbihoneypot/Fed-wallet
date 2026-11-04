package app.independentwallet.android

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Activity recreation/back never replaces a live service-owned wallet session. */
class WalletApplication : Application(), ViewModelStoreOwner {
    internal val locks by lazy { AppLockManager(this) }
    override val viewModelStore = ViewModelStore()
    val wallet: OfflineWalletViewModel get() =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[OfflineWalletViewModel::class.java]
}
