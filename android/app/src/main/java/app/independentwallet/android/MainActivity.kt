package app.independentwallet.android

import android.os.Bundle
import android.os.Build
import android.app.KeyguardManager
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity(){
    internal val offlineVm by lazy{(application as WalletApplication).wallet}
    private var pending by mutableStateOf<Pair<VaultAction,Long>?>(null)
    private var prompt by mutableStateOf(false)
    private var pinBusy by mutableStateOf(false)
    private var pinError by mutableStateOf<String?>(null)
    private var registered=false
    private var biometricCancellation:android.os.CancellationSignal?=null
    private var biometricAttempt=0L
    private val credentials=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val request=pending
        if(request!=null)lifecycleScope.launch{lifecycle.withResumed{completeSystemAuthentication(request,result.resultCode==Activity.RESULT_OK)}}
    }
    private val screenOff=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){
        NfcAddressOffer.clear();++biometricAttempt;biometricCancellation?.cancel();prompt=false;pending=null;offlineVm.screenOff()
    }}
    internal fun authenticate(action:VaultAction){
        if(pending!=null){offlineVm.isAuthenticationPending();return}
        val token=offlineVm.beginAuthentication(action)?:return
        pending=action to token;pinError=null
        if(action==VaultAction.EnableBiometric)launchBiometric(true)else prompt=true
    }
    @Suppress("DEPRECATION") private fun launchDeviceCredential(){
        val request=pending?:return;val manager=getSystemService(KeyguardManager::class.java)
        val intent=if(manager.isDeviceSecure)manager.createConfirmDeviceCredentialIntent("Authenticate wallet","Confirm your Android PIN, password or pattern")else null
        if(intent==null){cancelAuthentication();offlineVm.reportAuthenticationUnavailable();return}
        prompt=false
        try{credentials.launch(intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))}catch(_:Exception){if(pending==request)cancelAuthentication()}
    }
    private fun completeSystemAuthentication(request:Pair<VaultAction,Long>,approved:Boolean){
        if(pending!=request)return
        if(!approved){cancelAuthentication();return}
        lifecycleScope.launch{try{
            if(!offlineVm.authenticationCurrent(request.first,request.second)){cancelAuthentication();return@launch}
            val locks=application.appLocks()
            if(locks.configured()&&locks.migrating()){
                val grant=locks.securityGrant()
                // Keep healthy-wallet recovery available even if another legacy slot is damaged.
                try{withContext(Dispatchers.IO){locks.finishMigration(offlineVm.migrationSlots(),grant)}}catch(_:Exception){offlineVm.refreshSecurity()}
            }
            if(offlineVm.authenticationCurrent(request.first,request.second)){
                pending=null;prompt=false;offlineVm.finishAuthentication(request.first,request.second,true);offlineVm.refreshSecurity()
            }else cancelAuthentication()
        }catch(_:Exception){cancelAuthentication()}}
    }
    private fun submitPin(pin:String){
        val request=pending?:return;if(pinBusy)return
        pinBusy=true;pinError=null
        lifecycleScope.launch{
            var result:PinCheck?=null
            try{
                withContext(Dispatchers.IO){result=application.appLocks().checkPin(pin)}
                if(pending!=request||!offlineVm.authenticationCurrent(request.first,request.second))return@launch
                when(val r=result){
                    PinCheck.Duress->{prompt=false;pending=null;offlineVm.wipeFromDuress()}
                    is PinCheck.Rejected->pinError=r.message
                    is PinCheck.Unlocked->{application.appLocks().activate(r.root)
                        if(application.appLocks().migrating())launchDeviceCredential()
                        else{pending=null;prompt=false;offlineVm.finishAuthentication(request.first,request.second,true)}
                    }
                    null->pinError="Authentication unavailable. Try again."
                }
            }catch(_:Exception){if(pending==request)pinError="PIN unlock is unavailable. Saved wallet data was kept."}
            finally{(result as?PinCheck.Unlocked)?.close();pinBusy=false}
        }
    }
    private fun launchBiometric(enrolling:Boolean){
        val request=pending?:return
        val attempt=++biometricAttempt
        biometricCancellation?.cancel();biometricCancellation=null;prompt=false;pinError=null
        fun current()=pending==request&&attempt==biometricAttempt&&offlineVm.authenticationCurrent(request.first,request.second)
        fun fallback(message:String){
            if(!current())return
            ++biometricAttempt;biometricCancellation=null
            if(enrolling){cancelAuthentication();offlineVm.reportBiometricUnavailable(message)}
            else{pinError=message;prompt=true}
        }
        lifecycleScope.launch{try{
            lifecycle.withResumed{}
            if(!current())return@launch
            val capability=if(Build.VERSION.SDK_INT>=30)getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                .canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            else if(Build.VERSION.SDK_INT>=29){@Suppress("DEPRECATION") getSystemService(android.hardware.biometrics.BiometricManager::class.java).canAuthenticate()}else 0
            if(capability!=0){fallback(biometricErrorText(capability));return@launch}
            val locks=application.appLocks();val configured=locks.configured()
            val grant=if(enrolling)checkNotNull(offlineVm.currentSecurityGrant())else null
            val cipher=if(configured)withContext(Dispatchers.IO){locks.biometricCipher(enrolling,grant)}else null
            if(!current())return@launch
            val signal=android.os.CancellationSignal();biometricCancellation=signal
            val builder=android.hardware.biometrics.BiometricPrompt.Builder(this@MainActivity)
                .setTitle(if(enrolling)"Enable biometric unlock"else"Unlock wallet")
                .setNegativeButton(if(enrolling)"Cancel"else if(configured)"Use app PIN"else"Use device PIN",mainExecutor){_,_->
                    if(current()){
                        ++biometricAttempt;biometricCancellation=null
                        if(enrolling)cancelAuthentication()else if(configured)prompt=true else launchDeviceCredential()
                    }
                }
            if(Build.VERSION.SDK_INT>=30)builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val callback=object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult){
                    if(!current())return
                    if(enrolling&&!offlineVm.securityProofCurrent()){cancelAuthentication();return}
                    lifecycleScope.launch{
                        var root:ByteArray?=null
                        try{
                            if(cipher!=null){check(result.cryptoObject?.cipher===cipher);withContext(Dispatchers.IO){root=locks.completeBiometric(cipher,enrolling,grant)}}
                            if(!current())return@launch
                            root?.let{locks.activate(it)}
                            ++biometricAttempt;biometricCancellation=null
                            pending=null;prompt=false;offlineVm.finishAuthentication(request.first,request.second,true);offlineVm.refreshSecurity()
                        }catch(_:Exception){fallback("Biometric unlock could not finish. Use your app PIN; you can disable and enable biometrics again in Settings.")}
                        finally{root?.fill(0)}
                    }
                }
                override fun onAuthenticationError(code:Int,message:CharSequence){fallback(biometricErrorText(code))}
            }
            val biometric=builder.build()
            if(cipher!=null)biometric.authenticate(android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),signal,mainExecutor,callback)
            else biometric.authenticate(signal,mainExecutor,callback)
        }catch(_:android.security.keystore.KeyPermanentlyInvalidatedException){fallback("Fingerprints changed. Unlock with your app PIN, then disable and enable biometric unlock in Settings.")}
        catch(_:Exception){fallback("Biometrics are unavailable. Use PIN, or enable biometric unlock again in Settings.")}}
    }
    private fun cancelAuthentication(){
        val request=pending;++biometricAttempt;pending=null;prompt=false;biometricCancellation?.cancel();biometricCancellation=null;application.appLocks().lock()
        if(request!=null)offlineVm.finishAuthentication(request.first,request.second,false)
    }
    override fun onUserInteraction(){super.onUserInteraction();if(!AppWiper.pending(noBackupFilesDir))offlineVm.userInteraction()}
    override fun onStart(){super.onStart();if(!AppWiper.pending(noBackupFilesDir))offlineVm.resumePrivacy()}
    override fun onStop(){
        NfcAddressOffer.clear()
        if(!isChangingConfigurations&&biometricCancellation!=null){
            ++biometricAttempt;biometricCancellation?.cancel();biometricCancellation=null;prompt=pending!=null
        }
        if(!isChangingConfigurations&&!AppWiper.pending(noBackupFilesDir))offlineVm.background()
        super.onStop()
    }
    override fun onDestroy(){++biometricAttempt;biometricCancellation?.cancel();if(registered)unregisterReceiver(screenOff);super.onDestroy()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if(AppWiper.pending(noBackupFilesDir)){
            setContent{WalletTheme{Text("Clearing wallet data…",Modifier.padding(32.dp))}}
            lifecycleScope.launch(Dispatchers.IO){AppWiper.erase(applicationContext){}};return
        }
        pending=offlineVm.pendingAuthenticationRequest();prompt=pending!=null
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.decorView.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if(Build.VERSION.SDK_INT>=30)window.decorView.importantForContentCapture=View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        if(Build.VERSION.SDK_INT>=31)window.setHideOverlayWindows(true)
        androidx.core.content.ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);registered=true
        setContent{WalletTheme{
            val ui by offlineVm.state.collectAsStateWithLifecycle()
            LaunchedEffect(ui.generation,pending){if(pending!=null&&offlineVm.pendingAuthenticationRequest()!=pending){pending=null;prompt=false}}
            if(ui.wiping)Text("Clearing wallet data…",Modifier.padding(32.dp))else{
                WalletApp(offlineVm=offlineVm,authenticate=::authenticate)
                if(prompt&&pending!=null)AuthenticationDialog(application.appLocks().configured(),pinBusy,pinError,
                    allowBiometric=(!ui.security.pin||ui.security.biometrics)&&pending?.first!=VaultAction.ConfigureSecurity,
                    submit=::submitPin,biometric={launchBiometric(false)},device=::launchDeviceCredential,dismiss=::cancelAuthentication)
            }
        }}
    }
}
@Composable private fun WalletTheme(content: @Composable () -> Unit)=RetroWalletTheme(content)
private enum class Page(val title:String){Wallet("Wallet"),Node("Node"),Settings("Settings")}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WalletApp(vm:WalletViewModel=viewModel(),offlineVm:OfflineWalletViewModel,authenticate:(VaultAction)->Unit){
    val state by vm.state.collectAsStateWithLifecycle();val wallet by offlineVm.state.collectAsStateWithLifecycle()
    var page by remember{mutableStateOf(Page.Wallet)};var picker by remember{mutableStateOf(false)}
    val torChoice=if(state.loading||state.saving||state.settingsUnavailable||state.coreVersion==null)null else
        TorChoice(state.profiles.tor!=null,state.nodeKind==NodeKind.Tor){offlineVm.autoConnectCandidate(null,state.nodeKind);offlineVm.disconnect();vm.selectNodeKind(NodeKind.Tor);page=Page.Node}
    LaunchedEffect(state.savedNode,state.nodeKind,state.loading,state.saving,state.settingsUnavailable){
        if(!state.loading&&!state.saving){if(state.settingsUnavailable)offlineVm.disconnect()else offlineVm.nodeSelectionChanged(state.savedNode,state.nodeKind)}
    }
    LaunchedEffect(state.connectionNode,state.nodeKind,wallet.stage){offlineVm.autoConnectCandidate(state.connectionNode,state.nodeKind)}
    val scroll=rememberScrollState();LaunchedEffect(page,wallet.stage){scroll.scrollTo(0)}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={
        if(page==Page.Wallet)TextButton(onClick={picker=true},enabled=!wallet.busy){Text(wallet.walletName+" ▾",style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)}
        else Text(page.title,style=MaterialTheme.typography.titleLarge)
    })},bottomBar={NavigationBar{Page.entries.forEach{item->NavigationBarItem(selected=page==item,onClick={
        if(page==Page.Wallet&&item!=Page.Wallet&&wallet.stage in listOf(WalletStage.Backup,WalletStage.Verify,WalletStage.Recovery,WalletStage.Keys))offlineVm.lock()
        page=item
    },icon={Icon(when(item){Page.Wallet->Icons.Outlined.Home;Page.Node->Icons.AutoMirrored.Outlined.List;Page.Settings->Icons.Outlined.Settings},contentDescription=null)},label={Text(item.title)})}}}){insets->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(scroll).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            when(page){
                Page.Wallet->OfflineWalletPanel(wallet,offlineVm,authenticate,state.connectionNode,state.nodeKind,torChoice){page=Page.Node}
                Page.Node->NodeSettingsPanel(state,vm,wallet,offlineVm,authenticate){page=Page.Wallet}
                Page.Settings->WalletSettingsPanel(wallet,offlineVm,authenticate,state.coreVersion,{picker=true}){action->page=Page.Wallet;authenticate(action)}
            }
        }
    }
    if(picker)WalletPickerDialog(wallet,offlineVm){picker=false}
}
