package app.independentwallet.android

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.independentwallet.bindings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive

enum class WalletStage { Loading, Setup, Locked, Backup, Verify, Open, Recovery, Keys }
enum class VaultAction { StartSetup, Save, Unlock, DiscardSetup, Send, RetrySend, RevealSeed, MainnetCopy, RevealKeys, ConfigureSecurity, EnableBiometric }

internal fun allowedPreviewWallet(info: OfflineWalletInfo): Boolean =
    info.network == KeyNetwork.STAGENET || info.network == KeyNetwork.MAINNET

/** Redact accidental state formatting. Setup persistence is encrypted; never log. */
class SensitivePhrase(private var value: String) {
    fun text(): String = value
    fun clear() { value = "" }
    override fun toString(): String = "[recovery phrase hidden]"
}

data class OfflineUiState(
    val walletSlot: WalletSlot = WalletSlot.Mainnet,
    val wallets: List<WalletEntry> = emptyList(), val walletName:String = "My wallet",
    val security:SecurityStatus=SecurityStatus(), val securityAuthorized:Boolean=false,
    val keys:SensitiveKeys?=null, val wiping:Boolean=false,
    val creationNetwork: KeyNetwork = KeyNetwork.MAINNET,
    val mainnetSaved: Boolean = false,
    val stage: WalletStage = WalletStage.Loading,
    val hasVault: Boolean = false,
    val hasSetup: Boolean = false,
    val requestSetupAuthentication: Boolean = false,
    val requestDiscardAuthentication: Boolean = false,
    val busy: Boolean = false,
    val info: OfflineWalletInfo? = null,
    val phrase: SensitivePhrase? = null,
    val challenge: List<UInt> = emptyList(),
    val backupAnswers: List<SensitivePhrase> = emptyList(),
    val generation: Long = 0,
    val error: String? = null,
    val clipboardMessage: String? = null,
    val backgroundSyncCompleted: Boolean = false,
    val receiving: ReceivingAddressBook? = null,
    val receivingBusy: Boolean = false,
    val receivingError: String? = null,
    val online: OnlineUiState = OnlineUiState(),
)

class OfflineWalletViewModel(application: Application) : AndroidViewModel(application) {
    private val selection = application.getSharedPreferences("wallet-selection-v1", android.content.Context.MODE_PRIVATE)
    private var walletSlot = try { WalletSlot.fromId(selection.getString("slot","Mainnet")?:"Mainnet") } catch(_:Exception){WalletSlot.Mainnet}
    private val catalog=WalletCatalog(application)
    private val locks=application.appLocks()
    private var securityAuthorizedAt:Long?=null
    private var securityGrant:SecurityAuthorization.Grant?=null
    private var securityExpiry:Job?=null
    private var addingWallet=false
    private var vault = WalletVault(application, walletSlot)
    private var setupStore = SetupVault(application, walletSlot).store
    private var setupLease: SetupStore.SetupLease? = null
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setupWrite: Job? = null
    private var setupRetirement: Job? = null
    private var pendingDiscard: SetupStore.DiscardRequest? = null
    private var setupRetiring = 0
    private var cleared = false
    private var pendingCreation: (() -> OfflineWalletSession)? = null
    private var pendingCreationCleanup: (() -> Unit)? = null
    private var pendingMainnetHeight: ULong? = null
    private val seedClipboard = AndroidSeedClipboard(application)
    private val mutable = MutableStateFlow(OfflineUiState(walletSlot = walletSlot))
    val state = mutable.asStateFlow()
    fun acknowledgeReceiptOpened(id: String) {
        mutable.update { it.copy(online = consumeReceiptEvent(it.online, id)) }
    }
    private var session: OfflineWalletSession? = null
    private var onlineSession: OnlineWalletSession? = null
    private var receivingOperation: Job? = null
    private var networkOperation: Job? = null
    private var networkInFlight = false
    private var networkEpoch = 0L
    private var epoch = 0L
    private var vaultIoInFlight = false
    private var operation: Job? = null
    private var authenticationTimeout: Job? = null
    private var pendingAuthentication: Pair<Long, VaultAction>? = null
    private val authenticationWindow = AuthenticationWindow(SystemClock::elapsedRealtime)
    private val inactivity = InactivityWindow(SystemClock::elapsedRealtime)
    private var inactivityTimeout: Job? = null
    private var recoveryTimeout: Job? = null
    private var recoveryShownAt: Long? = null
    private var backgroundLocked = false
    private var appVisible = false
    private var connectionId = 0L
    private val nodeStore = NodeStore(application)
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preferenceWrite: Job? = null
    private var preferenceRevision = 0L
    private var rememberedConnection: RememberedConnection? = null
    private var connectionCandidate: Pair<String, NodeKind>? = null
    private var autoConnectEpoch: Long? = null
    private val syncLoop = ForegroundSyncLoop(viewModelScope, { delay(syncPollIntervalMs(mutable.value.online)) }, {
        val ui = mutable.value
        canPollNetwork(ui, pendingAuthentication != null) && !networkInFlight
    }, { pollNetwork() })

    init {
        viewModelScope.launch {
            try {
                val entries=withContext(Dispatchers.IO){catalog.initialize()}
                if(entries.none{it.slot==walletSlot}&&entries.isNotEmpty())walletSlot=entries.firstOrNull{it.network=="mainnet"}?.slot?:entries.first().slot
                vault=WalletVault(application,walletSlot);setupStore=SetupVault(application,walletSlot).store
                val (exists, draft, mainnet) = withContext(Dispatchers.IO) { Triple(vault.exists(), setupStore.exists(),
                    WalletVault(application, WalletSlot.Mainnet).exists() || SetupVault(application, WalletSlot.Mainnet).store.exists()) }
                mutable.update { it.copy(walletSlot=walletSlot,wallets=entries,walletName=entries.find{e->e.slot==walletSlot}?.name?:walletSlot.label,stage = if (exists || draft) WalletStage.Locked else WalletStage.Setup, hasVault = exists, hasSetup = draft, mainnetSaved = mainnet) }
                refreshSecurity()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unreadable vault is never treated as permission to overwrite it.
                val saved = withContext(Dispatchers.IO) { try { vault.exists() } catch (_: Exception) { true } }
                mutable.update { it.copy(stage = WalletStage.Locked, hasVault = saved, hasSetup = true, error = "Saved wallet or setup storage is unavailable. Existing files were kept.") }
            }
        }
    }

    fun selectCreationNetwork(network: KeyNetwork) {
        val before = mutable.value
        if (before.stage != WalletStage.Setup || before.busy || pendingCreation != null ||
            network != KeyNetwork.MAINNET) return
        mutable.update { it.copy(creationNetwork = network, error = null, generation = it.generation + 1) }
    }

    private fun refreshCatalog(){
        viewModelScope.launch{try{
            val entries=withContext(Dispatchers.IO){catalog.initialize()}
            mutable.update{it.copy(wallets=entries,walletName=entries.find{e->e.slot==walletSlot}?.name?:walletSlot.label)}
        }catch(_:Exception){mutable.update{it.copy(error="The wallet list is unavailable. Existing files were kept.")}}}
    }
    private fun registerWalletIdentity(info:OfflineWalletInfo,creating:Boolean){
        if(creating){
            for(entry in catalog.initialize().filter{it.address==null&&it.slot!=walletSlot}){
                val oldVault=WalletVault(getApplication(),entry.slot)
                if(oldVault.exists()){
                    val bytes=oldVault.read();var old:OfflineWalletSession?=null
                    try{old=OfflineWalletSession.openSnapshot(bytes);val detail=old.details();catalog.identify(entry.slot,detail.address,detail.network==KeyNetwork.MAINNET)}
                    finally{bytes.fill(0);old?.lock();old?.destroy()}
                }
            }
            check(catalog.read().none{it.slot!=walletSlot&&it.address==info.address}){"This wallet is already saved"}
        }
        catalog.identify(walletSlot,info.address,info.network==KeyNetwork.MAINNET);refreshCatalog()
    }
    fun addWallet(name:String){
        val ui=mutable.value
        if(addingWallet||ui.busy||vaultIoInFlight||networkInFlight||pendingAuthentication!=null||setupRetiring>0||
            ui.stage !in listOf(WalletStage.Setup,WalletStage.Open,WalletStage.Locked)||(ui.hasSetup&&!ui.hasVault))return
        addingWallet=true;val ticket=epoch;mutable.update{it.copy(busy=true)}
        viewModelScope.launch{try{
            val entry=withContext(Dispatchers.IO){catalog.add(name)};refreshCatalog()
            if(ticket==epoch){mutable.update{it.copy(busy=false)};selectWalletSlot(entry.slot)}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){if(ticket==epoch)mutable.update{it.copy(error="Use a name of at most 64 characters. Up to 50 wallets are supported.")}}
        finally{addingWallet=false;if(ticket==epoch)mutable.update{it.copy(busy=false)}}}
    }
    fun renameWallet(name:String){
        if(mutable.value.busy||pendingAuthentication!=null)return
        val target=walletSlot
        viewModelScope.launch{try{withContext(Dispatchers.IO){catalog.rename(target,name)};refreshCatalog()}
        catch(_:Exception){mutable.update{it.copy(error="Use a wallet name of at most 64 characters.")}}}
    }
    internal fun migrationSlots():List<WalletSlot> = (catalog.initialize().map{it.slot}+WalletSlot.entries).distinct()
    internal fun authenticationCurrent(action:VaultAction,ticket:Long)=pendingAuthentication==(ticket to action)&&ticket==epoch&&authenticationWindow.isValid()&&appVisible
    internal fun reportBiometricUnavailable(message:String){mutable.update{it.copy(error=message)};refreshSecurity()}
    fun refreshSecurity(){viewModelScope.launch{
        val status=withContext(Dispatchers.IO){try{SecurityStatus(locks.configured(),locks.biometricEnabled(),locks.duressEnabled(),locks.migrating())}
            catch(_:Exception){SecurityStatus(pin=locks.configured(),error="Security settings are unavailable. Saved wallets were kept.")}}
        mutable.update{it.copy(security=status)}
    }}
    internal fun currentSecurityGrant()=securityGrant?.takeIf{locks.grantCurrent(it)}
    internal fun securityProofCurrent():Boolean{val at=securityAuthorizedAt?:return false;val now=SystemClock.elapsedRealtime();return currentSecurityGrant()!=null&&now>=at&&now-at<SecurityAuthorization.LIFETIME_MS}
    private fun clearSecurityAuthorization(){securityAuthorizedAt=null;securityGrant=null;securityExpiry?.cancel();securityExpiry=null}
    internal fun canManageSecurity()=securityProofCurrent()&&pendingAuthentication==null&&!mutable.value.busy&&!networkInFlight&&!vaultIoInFlight&&setupLease==null&&setupRetiring==0
    fun setAppPin(pin:String,repeat:String)=securityWork{grant->
        require(pin==repeat){"The PINs do not match"};require(validPin(pin)){"Use an 8–12 digit PIN"}
        locks.setPin(pin,grant);locks.finishMigration(migrationSlots(),grant)
    }
    fun setDuressPin(pin:String,repeat:String,consent:Boolean)=securityWork{grant->
        require(consent){"Confirm that this code erases all data in this app"};require(pin==repeat){"The PINs do not match"};locks.setDuress(pin,grant)
    }
    fun disableDuressPin()=securityWork{locks.disableDuress(it)}
    fun disableBiometrics()=securityWork{locks.disableBiometrics(it)}
    private fun securityWork(work:(SecurityAuthorization.Grant)->Unit){
        if(!canManageSecurity()){mutable.update{it.copy(securityAuthorized=false,error="Authenticate again to change security settings.")};return}
        val grant=currentSecurityGrant()?:return;val ticket=epoch;mutable.update{it.copy(busy=true,error=null)}
        operation=viewModelScope.launch{try{
            withContext(Dispatchers.IO){work(grant)}
            if(ticket==epoch)mutable.update{it.copy(online=it.online.copy(message="Security settings saved."))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(e:IllegalArgumentException){if(ticket==epoch)mutable.update{it.copy(error=e.message?:"Check the security settings.")}}
        catch(_:Exception){if(ticket==epoch)mutable.update{it.copy(error="Security setup could not finish. Existing data was kept; authenticate again to resume PIN setup.")}}
        finally{if(ticket==epoch){clearSecurityAuthorization();mutable.update{it.copy(busy=false,securityAuthorized=false)};userInteraction()};refreshSecurity()}}
    }
    private fun revealKeysAuthenticated(){
        try{disconnect();val keys=SensitiveKeys(checkNotNull(session).exportKeys());recoveryShownAt=SystemClock.elapsedRealtime()
            mutable.update{it.copy(stage=WalletStage.Keys,keys=keys,busy=false,generation=it.generation+1)}
            userInteraction();recoveryTimeout?.cancel();recoveryTimeout=viewModelScope.launch{delay(60_000);lock()}
        }catch(_:Exception){mutable.update{it.copy(busy=false,error="This wallet's keys are unavailable.")}}
    }
    fun copyWalletKey(name:String){
        if(mutable.value.stage!=WalletStage.Keys||recoveryExpired()){lock();return}
        val text=mutable.value.keys?.text(name)?:return
        val copied=seedClipboard.copy(text);mutable.update{it.copy(clipboardMessage=if(copied)"Copied. Use Clear copy when finished."else"Clipboard access failed.")}
    }
    internal fun wipeFromDuress(){
        locks.lock();clearSecurityAuthorization();mutable.value.keys?.clear();mutable.update{it.copy(wiping=true,busy=true,error=null)}
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{
            try{AppWiper.markPending(getApplication())}catch(_:Exception){}
            try{withContext(Dispatchers.Main.immediate){try{lockInternal()}finally{mutable.update{it.copy(wiping=true,busy=true,error=null)}}}}
            finally{AppWiper.erase(getApplication()){}}
        }
    }

    fun prepareMainnetCopy(height: ULong): Boolean {
        val before = mutable.value
        if (height > UInt.MAX_VALUE.toULong() || before.busy || vaultIoInFlight || networkInFlight ||
            before.stage != WalletStage.Open || !before.hasVault || walletSlot != WalletSlot.Original ||
            before.info?.mode != KeyWalletMode.SOFTWARE || before.info?.network != KeyNetwork.STAGENET) return false
        pendingMainnetHeight = height
        return true
    }

    /** Selection locks the old owner; completed wallets and drafts are never moved/deleted. */
    fun selectWalletSlot(target: WalletSlot) {
        if (target == walletSlot) return
        val before = mutable.value
        if (before.busy || vaultIoInFlight || networkInFlight || setupRetiring > 0 || setupWrite?.isActive == true || pendingAuthentication != null) return
        if (before.stage !in listOf(WalletStage.Setup, WalletStage.Locked, WalletStage.Open) ||
            (before.stage == WalletStage.Open && !before.hasVault)) {
            mutable.update { it.copy(error = "Pause this setup before switching wallets.") }; return
        }
        lockInternal()
        if (setupRetiring > 0) return
        val application = getApplication<Application>()
        val nextVault = WalletVault(application, target)
        val nextSetup = SetupVault(application, target).store
        vaultIoInFlight = true
        mutable.update { it.copy(stage = WalletStage.Loading, busy = true) }
        operation = viewModelScope.launch {
            var loaded: Triple<Boolean, Boolean, Boolean>? = null
            var entries=before.wallets
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val status = Triple(nextVault.exists(), nextSetup.exists(),
                        WalletVault(application, WalletSlot.Mainnet).exists() || SetupVault(application, WalletSlot.Mainnet).store.exists())
                    entries=catalog.initialize()
                    check(selection.edit().putString("slot", target.name).commit())
                    loaded = status
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The finalizer restores a locked, retryable selection. */
            } finally {
                vaultIoInFlight = false
                val status = loaded
                if (status != null) {
                    walletSlot = target; vault = nextVault; setupStore = nextSetup
                    mutable.value = OfflineUiState(walletSlot = target, wallets=entries,walletName=entries.find{it.slot==target}?.name?:target.label,security=mutable.value.security,hasVault = status.first, hasSetup = status.second,
                        mainnetSaved = status.third, stage = if (status.first || status.second) WalletStage.Locked else WalletStage.Setup,
                        generation = mutable.value.generation + 1)
                } else mutable.update { it.copy(stage = if (it.hasVault || it.hasSetup) WalletStage.Locked else WalletStage.Setup,
                    busy = false, error = "Wallet selection could not be saved. Existing wallets were kept.") }
            }
        }
    }

    private fun install(cleanup: (() -> Unit)? = null, create: () -> OfflineWalletSession) {
        if (mutable.value.busy || mutable.value.hasVault || mutable.value.hasSetup || mutable.value.stage != WalletStage.Setup || pendingCreation != null) { cleanup?.invoke(); return }
        pendingCreation = create
        pendingCreationCleanup = cleanup
        mutable.update { it.copy(requestSetupAuthentication = true, error = null) }
    }

    private fun createSetupAuthenticated(ticket: Long) {
        val create = pendingCreation ?: return
        val abandoned = pendingCreationCleanup
        var consumed = false
        pendingCreation = null
        pendingCreationCleanup = null // the returned object is owned by the guard below
        vaultIoInFlight = true
        operation = viewModelScope.launch {
            var created: OfflineWalletSession? = null
            var lease: SetupStore.SetupLease? = null
            try {
                withContext(Dispatchers.Default) { created = create(); consumed = true }
                if (ticket != epoch) return@launch
                val opened = checkNotNull(created)
                val info = opened.details()
                check(allowedPreviewWallet(info))
                check(info.network == KeyNetwork.MAINNET)
                withContext(Dispatchers.IO){registerWalletIdentity(info,creating=true)}
                val stage = if (info.backupVerified) WalletStage.Open else WalletStage.Backup
                val bytes = checkpoint(opened, stage, emptyList())
                try { withContext(Dispatchers.IO) { lease = setupStore.create(bytes) } }
                finally { bytes.fill(0) }
                if (ticket != epoch) return@launch
                val phrase = if (info.backupVerified) null else SensitivePhrase(opened.recoveryPhrase())
                session = opened
                created = null
                setupLease = lease; lease = null
                mutable.update { it.copy(stage = stage, hasSetup = true,
                    info = info, phrase = phrase, busy = true, generation = ticket) }
                userInteraction()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: WalletException) {
                if (ticket == epoch) mutable.update { it.copy(busy = false,
                    error = if (error is WalletException.InvalidInput) error.detail else "Wallet input is invalid.") }
            } catch (_: LinkageError) {
                if (ticket == epoch) mutable.update { it.copy(busy = false, error = "The Rust wallet core is unavailable.") }
            } catch (_: Exception) {
                if (ticket == epoch) mutable.update { it.copy(busy = false, error = "The offline wallet could not be opened.") }
            } finally {
                created?.lock()
                created?.destroy()
                if (!consumed) abandoned?.invoke()
                lease?.close()
                val exists = withContext(NonCancellable + Dispatchers.IO) { try { setupStore.exists() } catch (_: Exception) { true } }
                vaultIoInFlight = false
                if (session == null) mutable.update { it.copy(hasSetup = exists, busy = setupRetiring > 0,
                    stage = if (it.hasVault || exists) WalletStage.Locked else WalletStage.Setup) }
                else if (ticket == epoch) mutable.update { it.copy(busy = setupRetiring > 0) }
            }
        }
    }

    fun generate(format: RecoveryFormat) {
        val network = mutable.value.creationNetwork
        install { OfflineWalletSession.generate(network, format) }
    }
    fun importKeystone(scanner: KeystonePairingSession, height: ULong?) {
        try {
            val prepared = scanner.finish(height)
            install(cleanup = { prepared.lock(); prepared.destroy() }) { prepared }
        } catch (_: Exception) { mutable.update { it.copy(error = "The Keystone account could not be opened.") } }
    }
    fun restore(format: RecoveryFormat, words: String, height: ULong) {
        val network = mutable.value.creationNetwork
        install { OfflineWalletSession.restoreSeed(network, format, words, height) }
    }
    fun importSpendKey(hex: String, height: ULong) {
        val network = mutable.value.creationNetwork
        install { OfflineWalletSession.importSpendKey(network, hex, height) }
    }
    fun importViewOnly(address: String, hex: String, height: ULong) {
        val network = mutable.value.creationNetwork
        install { OfflineWalletSession.importViewOnly(network, address, hex, height) }
    }

    fun checkBackup() {
        if (mutable.value.stage != WalletStage.Backup || mutable.value.busy) return
        val current = session ?: return
        val challenge = current.backupChallenge()
        mutable.value.phrase?.clear()
        saveSetupState(mutable.value.copy(stage = WalletStage.Verify, phrase = null, challenge = challenge,
            backupAnswers = List(3) { SensitivePhrase("") }, error = null))
    }

    fun confirmBackup(answers: List<String>) {
        if (mutable.value.stage != WalletStage.Verify || mutable.value.busy) return
        try {
            val current = session ?: return
            current.confirmBackup(answers)
            mutable.value.backupAnswers.forEach { it.clear() }
            saveSetupState(mutable.value.copy(stage = WalletStage.Open, info = current.details(), challenge = emptyList(), backupAnswers = emptyList(), error = null))
            userInteraction()
        } catch (_: WalletException) {
            mutable.update { it.copy(error = "Those words do not match. Check your written recovery phrase.") }
        }
    }

    fun updateBackupAnswer(index: Int, value: String) {
        if (mutable.value.stage != WalletStage.Verify || mutable.value.busy || index !in 0..2 || value.length > 32) return
        val answers = mutable.value.backupAnswers.toMutableList()
        if (answers.size != 3) return
        answers[index].clear(); answers[index] = SensitivePhrase(value)
        mutable.update { it.copy(backupAnswers = answers) }
    }

    private fun checkpoint(current: OfflineWalletSession, stage: WalletStage, answers: List<SensitivePhrase>): ByteArray {
        val step = when (stage) { WalletStage.Backup -> SetupStep.Backup; WalletStage.Verify -> SetupStep.Verify; WalletStage.Open -> SetupStep.Ready; else -> error("Invalid setup stage") }
        return SetupRecord(step, current.snapshotForSetup(), List(3) { i -> if (step == SetupStep.Verify) answers.getOrNull(i)?.text().orEmpty() else "" }).use { it.encode() }
    }

    private fun queueSetupWrite(lease: SetupStore.SetupLease, bytes: ByteArray, complete: ((Boolean) -> Unit)? = null): Job {
        val previous = setupWrite
        return setupScope.launch {
            previous?.join()
            val success = try { lease.write(bytes); true } catch (_: Exception) { false } finally { bytes.fill(0) }
            withContext(Dispatchers.Main.immediate) { complete?.invoke(success) }
        }.also { setupWrite = it }
    }

    private fun saveSetupState(next: OfflineUiState) {
        val current = session ?: return
        val lease = setupLease ?: return
        val bytes = checkpoint(current, next.stage, next.backupAnswers)
        val ticket = epoch
        mutable.value = next.copy(busy = true)
        queueSetupWrite(lease, bytes) { success ->
            if (ticket == epoch) mutable.update { it.copy(busy = false, error = if (success) null else "The latest setup step could not be saved. The earlier encrypted setup was kept.") }
        }
    }

    /** Called only after the UI's discard confirmation. Pause writes finish first. */
    fun requestDiscardSetup() {
        val before = mutable.value
        if (before.busy || before.hasVault || !before.hasSetup ||
            before.stage !in listOf(WalletStage.Locked, WalletStage.Backup, WalletStage.Verify, WalletStage.Open)) return
        lockInternal()
        val ticket = epoch
        val retiring = setupRetirement
        vaultIoInFlight = true
        mutable.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            var prepared: SetupStore.DiscardRequest? = null
            try {
                prepared = withContext(Dispatchers.IO) {
                    retiring?.join()
                    check(!vault.exists()) { "A completed wallet is already saved" }
                    setupStore.prepareDiscard()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == epoch) mutable.update { it.copy(error = "Setup could not be prepared for discard. Saved files were kept.") }
            } finally {
                val saved = withContext(NonCancellable + Dispatchers.IO) { try { vault.exists() } catch (_: Exception) { true } }
                val draft = withContext(NonCancellable + Dispatchers.IO) { try { setupStore.exists() } catch (_: Exception) { true } }
                vaultIoInFlight = false
                val ready = ticket == epoch && !saved && draft && prepared != null
                if (ready) pendingDiscard = prepared
                if (session == null) mutable.update { it.copy(hasVault = saved, hasSetup = draft,
                    stage = if (saved || draft) WalletStage.Locked else WalletStage.Setup,
                    requestDiscardAuthentication = ready,
                    busy = setupRetiring > 0) }
            }
        }
    }

    /** Only an action and epoch cross the system credential prompt, never a seed. */
    fun beginAuthentication(action: VaultAction): Long? {
        if (inactivity.expired()) { lock(); return null }
        val before = mutable.value
        if (before.busy || (before.receivingBusy && action != VaultAction.Unlock) || vaultIoInFlight || (action == VaultAction.StartSetup && (before.stage != WalletStage.Setup || before.hasVault || before.hasSetup || pendingCreation == null)) ||
            (action == VaultAction.Save && (before.stage != WalletStage.Open || before.hasVault)) ||
            (action == VaultAction.Unlock && before.stage != WalletStage.Locked) ||
            (action == VaultAction.DiscardSetup && (before.stage != WalletStage.Locked || before.hasVault || !before.hasSetup || pendingDiscard == null)) ||
            (action == VaultAction.RevealSeed && (before.stage != WalletStage.Open || !before.hasVault ||
                before.info?.mode != KeyWalletMode.SOFTWARE || before.info?.seedFormat == null || networkInFlight)) ||
            (action == VaultAction.RevealKeys && (before.stage != WalletStage.Open || !before.hasVault || networkInFlight)) ||
            (action == VaultAction.ConfigureSecurity && (before.stage !in listOf(WalletStage.Open,WalletStage.Locked,WalletStage.Setup) || networkInFlight || setupLease!=null || setupRetiring>0 || setupWrite?.isActive==true)) ||
            (action == VaultAction.EnableBiometric && !canManageSecurity()) ||
            (action == VaultAction.MainnetCopy && (before.stage != WalletStage.Open || !before.hasVault ||
                before.walletSlot != WalletSlot.Original || before.info?.mode != KeyWalletMode.SOFTWARE ||
                before.info?.network != KeyNetwork.STAGENET || pendingMainnetHeight == null || networkInFlight || setupRetiring > 0 || setupWrite?.isActive == true)) ||
            (action in listOf(VaultAction.Send, VaultAction.RetrySend) &&
                (before.stage != WalletStage.Open || !before.hasVault || networkInFlight || onlineSession == null ||
                    (action == VaultAction.Send && before.online.review == null) ||
                    (action == VaultAction.RetrySend && before.online.status?.pending == null)))) return null
        ++epoch
        inactivityTimeout?.cancel()
        inactivity.clear()
        pendingAuthentication = epoch to action
        authenticationWindow.begin()
        before.phrase?.clear()
        mutable.update { it.copy(phrase = null, requestSetupAuthentication = false, requestDiscardAuthentication = false,
            busy = true, error = null, generation = it.generation + 1) }
        authenticationTimeout?.cancel()
        authenticationTimeout = viewModelScope.launch {
            delay(AuthenticationWindow.LIFETIME_MS)
            lock()
        }
        return epoch
    }

    fun finishAuthentication(action: VaultAction, ticket: Long, approved: Boolean) {
        if (pendingAuthentication != (ticket to action) || ticket != epoch) return
        if (!authenticationWindow.isValid()) { lock(); return }
        authenticationTimeout?.cancel()
        authenticationWindow.clear()
        pendingAuthentication = null
        if (!approved) {
            clearSecurityAuthorization()
            pendingMainnetHeight = null
            pendingCreation = null; pendingCreationCleanup?.invoke(); pendingCreationCleanup = null
            pendingDiscard = null
            mutable.update { it.copy(busy = false, securityAuthorized=false,error = "Authentication was cancelled.") }
            userInteraction()
            maybeReconnect()
            if (canPollNetwork(mutable.value, false)) syncLoop.start()
            return
        }
        if (action == VaultAction.Unlock && backgroundLocked) {
            val current = session
            if (current == null) { lock(); return }
            backgroundLocked = false
            mutable.update { it.copy(stage = WalletStage.Open, info = current.details(), busy = false,
                error = null, generation = it.generation + 1,
                online = it.online.copy(backgroundActive = false)) }
            userInteraction()
            if (canPollNetwork(mutable.value, false)) syncLoop.start()
            return
        }
        if (action == VaultAction.StartSetup) { createSetupAuthenticated(ticket); return }
        if (action == VaultAction.MainnetCopy) {
            val height = pendingMainnetHeight.also { pendingMainnetHeight = null } ?: run { lock(); return }
            copyToMainnetAuthenticated(ticket, height); return
        }
        if (action == VaultAction.RevealSeed) {
            revealSeedAuthenticated()
            return
        }
        if(action==VaultAction.RevealKeys){revealKeysAuthenticated();return}
        if(action==VaultAction.ConfigureSecurity){
            disconnect();securityAuthorizedAt=SystemClock.elapsedRealtime();securityGrant=locks.securityGrant()
            securityExpiry?.cancel();securityExpiry=viewModelScope.launch{delay(SecurityAuthorization.LIFETIME_MS);clearSecurityAuthorization();mutable.update{it.copy(securityAuthorized=false)}}
            mutable.update{it.copy(busy=false,securityAuthorized=true)};userInteraction();return
        }
        if(action==VaultAction.EnableBiometric){mutable.update{it.copy(busy=false)};refreshSecurity();userInteraction();return}
        if (action == VaultAction.Send || action == VaultAction.RetrySend) {
            mutable.update { it.copy(busy = false) }
            userInteraction()
            sendAuthenticated(action)
            return
        }
        val discarding = if (action == VaultAction.DiscardSetup) pendingDiscard.also { pendingDiscard = null } else null
        vaultIoInFlight = true
        operation = viewModelScope.launch {
            var opened: OfflineWalletSession? = null
            var resumed: SetupStore.OpenSetup? = null
            var record: SetupRecord? = null
            var saveCompleted = false
            try {
                if (action == VaultAction.DiscardSetup) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        check(!vault.exists()) { "A completed wallet is already saved" }
                        checkNotNull(discarding).commit()
                    }
                    if (ticket == epoch) mutable.update { OfflineUiState(walletSlot = walletSlot,wallets=it.wallets,walletName=it.walletName,security=it.security,mainnetSaved = it.mainnetSaved, stage = WalletStage.Setup, busy = true, generation = it.generation + 1) }
                } else if (action == VaultAction.Save) {
                    val current = session ?: return@launch
                    withContext(Dispatchers.IO) {
                        val plaintext = current.snapshotForVault()
                        try { vault.create(plaintext) } finally { plaintext.fill(0) }
                    }
                    saveCompleted = true
                    val lease = setupLease; setupLease = null
                    if (lease != null) withContext(NonCancellable + Dispatchers.IO) {
                        setupWrite?.join()
                        try { lease.discard() } catch (_: Exception) { /* The completed wallet is already durable. */ }
                        finally { lease.close() }
                    }
                    if (ticket == epoch) mutable.update { it.copy(hasVault = true, hasSetup = false, busy = true, error = null) }
                } else {
                    withContext(Dispatchers.IO) {
                        if (mutable.value.hasVault) {
                            val plaintext = vault.read()
                            try { opened = OfflineWalletSession.openSnapshot(plaintext) } finally { plaintext.fill(0) }
                        } else {
                            val draft = setupStore.open(); resumed = draft
                            try {
                                val decoded = SetupRecord.decode(draft.plaintext); record = decoded
                                opened = OfflineWalletSession.openSetupSnapshot(decoded.snapshot)
                            } finally { draft.plaintext.fill(0) }
                        }
                    }
                    if (ticket != epoch) return@launch
                    val current = checkNotNull(opened)
                    val info = current.details()
                    check(allowedPreviewWallet(info))
                    check(walletSlot != WalletSlot.Mainnet || info.network == KeyNetwork.MAINNET)
                    withContext(Dispatchers.IO){try{registerWalletIdentity(info,creating=false)}catch(_:Exception){/* Preserve wallet access when metadata fails. */}}
                    val step = record?.step
                    check(step == null || (step == SetupStep.Ready) == info.backupVerified)
                    val stage = when (step) { SetupStep.Backup -> WalletStage.Backup; SetupStep.Verify -> WalletStage.Verify; else -> WalletStage.Open }
                    val phrase = if (stage == WalletStage.Backup) SensitivePhrase(current.recoveryPhrase()) else null
                    val challenge = if (stage == WalletStage.Verify) current.backupChallenge() else emptyList()
                    val answers = if (stage == WalletStage.Verify) record!!.answers.map { SensitivePhrase(it) } else emptyList()
                    session = current
                    opened = null
                    setupLease = resumed?.lease; resumed = null
                    mutable.update { it.copy(stage = stage, info = info, phrase = phrase, challenge = challenge,
                        backupAnswers = answers, busy = true, error = null, generation = it.generation + 1) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                if (ticket == epoch) mutable.update { it.copy(busy = false, error = "The Rust wallet core is unavailable.") }
            } catch (_: Exception) {
                if (ticket == epoch) mutable.update { it.copy(busy = action == VaultAction.DiscardSetup,
                    error = if (action == VaultAction.DiscardSetup) "Setup could not be discarded. Saved files were kept; resume or try again."
                    else "The wallet could not be ${if (action == VaultAction.Save) "saved" else "unlocked"}. Check your screen lock and authenticate again. The saved file was kept.") }
            } finally {
                opened?.lock()
                opened?.destroy()
                resumed?.close(); record?.close()
                // Account for an atomic write completing after background cancellation.
                val exists = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    try { vault.exists() } catch (_: Exception) { true }
                }
                val setupExists = withContext(NonCancellable + Dispatchers.IO) { try { setupStore.exists() } catch (_: Exception) { true } }
                vaultIoInFlight = false
                if (ticket == epoch && action == VaultAction.Save && exists && !saveCompleted) {
                    // File existence is not proof that this session was saved.
                    // Another activity (or a failed/partial save) may own it.
                    val error = mutable.value.error ?: "A saved wallet was kept. Unlock it to check its address."
                    lock()
                    mutable.update { it.copy(stage = WalletStage.Locked, hasVault = true, busy = false, error = error) }
                }
                else if (ticket == epoch) mutable.update { it.copy(hasVault = exists, hasSetup = setupExists, busy = false,
                    stage = if (action == VaultAction.DiscardSetup && !exists && !setupExists) WalletStage.Setup else it.stage,
                    error = if (action == VaultAction.DiscardSetup && !exists && !setupExists) null else it.error) }
                else if (session == null) mutable.update { it.copy(hasVault = exists, hasSetup = setupExists, busy = setupRetiring > 0,
                    stage = if (exists || setupExists) WalletStage.Locked else WalletStage.Setup) }
                if (ticket == epoch) {
                    userInteraction()
                    if (mutable.value.stage == WalletStage.Open && mutable.value.hasVault) restoreConnectionPreference()
                }
            }
        }
    }

    private fun copyToMainnetAuthenticated(ticket: Long, height: ULong) {
        val current = session ?: run { lock(); return }
        disconnect()
        val application = getApplication<Application>()
        val destination = WalletVault(application, WalletSlot.Mainnet)
        val draft = SetupVault(application, WalletSlot.Mainnet).store
        vaultIoInFlight = true
        operation = viewModelScope.launch {
            var copied: OfflineWalletSession? = null
            var durable = false
            try {
                withContext(Dispatchers.IO) { check(!destination.exists() && !draft.exists()) }
                withContext(Dispatchers.Default) { copied = current.copyToMainnet(height) }
                withContext(NonCancellable + Dispatchers.IO) {
                    check(!draft.exists())
                    val bytes = checkNotNull(copied).snapshotForVault()
                    try { destination.create(bytes); durable = true } finally { bytes.fill(0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == epoch) mutable.update { it.copy(error = "A mainnet copy could not be created. Existing wallets were kept; open the Mainnet wallet slot if it already contains a wallet or setup.") }
            } finally {
                copied?.lock(); copied?.destroy()
                vaultIoInFlight = false
                if (durable) {
                    lockInternal()
                    selectWalletSlot(WalletSlot.Mainnet)
                } else if (ticket == epoch) { mutable.update { it.copy(busy = false) }; userInteraction() }
            }
        }
    }

    fun isAuthenticationPending(): Boolean {
        if (pendingAuthentication != null && !authenticationWindow.isValid()) lock()
        return pendingAuthentication != null
    }

    internal fun pendingAuthenticationRequest(): Pair<VaultAction, Long>? =
        if (isAuthenticationPending()) pendingAuthentication?.let { it.second to it.first } else null

    fun reportAuthenticationUnavailable() {
        pendingMainnetHeight = null
        pendingCreation = null; pendingCreationCleanup?.invoke(); pendingCreationCleanup = null
        pendingAuthentication = null
        pendingDiscard = null
        authenticationWindow.clear()
        authenticationTimeout?.cancel()
        mutable.update { it.copy(busy = false, requestSetupAuthentication = false, requestDiscardAuthentication = false,
            error = "Set an Android PIN, password or pattern to protect, resume or discard your wallet setup.") }
        userInteraction()
    }

    private fun revealSeedAuthenticated() {
        try {
            val current = checkNotNull(session)
            // Stop online work before showing the saved recovery phrase.
            disconnect()
            val phrase = SensitivePhrase(current.recoveryPhrase())
            recoveryShownAt = SystemClock.elapsedRealtime()
            mutable.update { it.copy(stage = WalletStage.Recovery, phrase = phrase, busy = false,
                clipboardMessage = null, generation = it.generation + 1) }
            userInteraction()
            recoveryTimeout?.cancel()
            recoveryTimeout = viewModelScope.launch { delay(60_000); lock() }
        } catch (_: Exception) {
            mutable.update { it.copy(busy = false, error = "This wallet's recovery phrase is unavailable.") }
            userInteraction()
        }
    }

    fun hideRecovery() {
        if (mutable.value.stage !in listOf(WalletStage.Recovery,WalletStage.Keys)) return
        recoveryTimeout?.cancel()
        recoveryShownAt = null
        mutable.value.phrase?.clear();mutable.value.keys?.clear()
        mutable.update { it.copy(stage = WalletStage.Open, phrase = null, keys=null,clipboardMessage = null,
            generation = it.generation + 1) }
        userInteraction()
    }

    fun copyRecoveryPhrase() {
        val before = mutable.value
        if (before.busy || before.stage !in listOf(WalletStage.Backup, WalletStage.Recovery)) return
        if (recoveryExpired()) { lock(); return }
        val text = before.phrase?.text()?.takeIf { it.isNotEmpty() } ?: return
        val copied = seedClipboard.copy(text)
        mutable.update { it.copy(clipboardMessage = if (copied)
            "Copied. Leaving the app keeps this copy. Use Clear copy when finished; Android may clear it independently."
            else "Clipboard access failed. Keep your written backup.") }
    }

    fun clearSeedClipboard() {
        seedClipboard.clear()
        mutable.update { it.copy(clipboardMessage = null) }
    }

    fun userInteraction() {
        if (backgroundLocked) return
        if (mutable.value.stage !in listOf(WalletStage.Backup, WalletStage.Verify, WalletStage.Open, WalletStage.Recovery) || pendingAuthentication != null) return
        if (inactivity.expired() || recoveryExpired()) { lock(); return }
        inactivity.touch()
        inactivityTimeout?.cancel()
        inactivityTimeout = viewModelScope.launch { delay(InactivityWindow.LIFETIME_MS); if (inactivity.expired()) background() }
    }

    fun resumePrivacy() {
        appVisible = true
        if (backgroundLocked) return
        if (inactivity.expired() || recoveryExpired()) lock()
        maybeReconnect()
    }

    private fun recoveryExpired(): Boolean {
        val shown = recoveryShownAt ?: return false
        val now = SystemClock.elapsedRealtime()
        return now < shown || now - shown >= 60_000
    }

    /** Lock the UI; retain only consented scanning/polling or an already approved publication. */
    fun background() {
        appVisible = false
        if (!isAuthenticationPending() && !networkInFlight && !mutable.value.online.submitting) {
            val current = onlineSession
            val hadReview = mutable.value.online.review != null
            mutable.update { it.copy(online = it.online.copy(composingPayment = false, review = null, paymentError = false,
                phase = if (it.online.composingPayment || it.online.review != null) { if (it.online.status?.synced == true) it.online.route?.caughtUp ?: "Synced" else "Sync paused" } else it.online.phase,
                error = if (it.online.paymentError) null else it.online.error)) }
            // No native work is in flight: this only drops local memory, with
            // no storage/network. Complete it before another foreground quote
            // can start; a queued discard could otherwise erase a newer review.
            if (hadReview && current != null) try { current.discardReview() } catch (_: Exception) { }
        }
        if (!isAuthenticationPending() && canContinueBackground(mutable.value, false)) {
            if (backgroundLocked) return
            backgroundLocked = true
            locks.lock();clearSecurityAuthorization();mutable.value.keys?.clear()
            inactivityTimeout?.cancel(); inactivity.clear()
            mutable.value.phrase?.clear()
            mutable.update { it.copy(stage = WalletStage.Locked, info = null, phrase = null, keys=null,securityAuthorized=false,
                challenge = emptyList(), backupAnswers = emptyList(), generation = it.generation + 1,
                online = it.online.copy(backgroundActive = true, review = null)) }
            if (canPollNetwork(mutable.value, false)) syncLoop.start()
            android.util.Log.i("WalletNetwork", "sync_continues_background")
        }
        else if (!isAuthenticationPending()) lockInternal()
        else {
            mutable.value.phrase?.clear()
            mutable.update { it.copy(phrase = null, generation = it.generation + 1) }
        }
    }

    fun lock() = lockInternal()
    fun screenOff() { if (pendingAuthentication != null) lockInternal() else background() }

    private fun lockInternal() {
        locks.lock();clearSecurityAuthorization();mutable.value.keys?.clear()
        ++preferenceRevision
        rememberedConnection = null
        pendingMainnetHeight = null
        ++connectionId
        backgroundLocked = false
        getApplication<Application>().stopService(android.content.Intent(getApplication(), WalletSyncService::class.java))
        syncLoop.stop()
        ++epoch
        ++networkEpoch
        pendingAuthentication = null
        pendingDiscard = null
        authenticationWindow.clear()
        authenticationTimeout?.cancel()
        inactivityTimeout?.cancel()
        recoveryTimeout?.cancel()
        recoveryShownAt = null
        inactivity.clear()
        val lease = setupLease
        setupLease = null
        if (lease != null) {
            val current = session
            if (current != null && !mutable.value.hasVault) {
                try { queueSetupWrite(lease, checkpoint(current, mutable.value.stage, mutable.value.backupAnswers)) { success ->
                    if (!success && session == null) mutable.update { it.copy(error = "The latest setup change could not be saved. The earlier encrypted setup was kept.") }
                } } catch (_: Exception) { /* Preserve the last durable checkpoint. */ }
            }
            val pendingWrite = setupWrite
            setupRetiring++
            setupRetirement = setupScope.launch {
                try { pendingWrite?.join() } finally { lease.close() }
                withContext(Dispatchers.Main.immediate) {
                    setupRetiring--
                    if (session == null) mutable.update { it.copy(busy = vaultIoInFlight || setupRetiring > 0) }
                    if (cleared && setupRetiring == 0) setupScope.cancel()
                }
            }
        }
        pendingCreation = null; pendingCreationCleanup?.invoke(); pendingCreationCleanup = null
        operation?.cancel()
        receivingOperation?.cancel()
        onlineSession?.disconnect()
        onlineSession?.destroy()
        onlineSession = null
        networkOperation?.cancel()
        session?.lock()
        session?.destroy()
        session = null
        mutable.value.phrase?.clear()
        mutable.value.backupAnswers.forEach { it.clear() }
        mutable.update { OfflineUiState(walletSlot = walletSlot,wallets=it.wallets,walletName=it.walletName,security=it.security,wiping=it.wiping,creationNetwork = it.creationNetwork, mainnetSaved = it.mainnetSaved, stage = when {
            it.stage == WalletStage.Loading -> WalletStage.Loading
            it.hasVault || it.hasSetup -> WalletStage.Locked
            else -> WalletStage.Setup
        }, hasVault = it.hasVault, hasSetup = it.hasSetup, busy = vaultIoInFlight || setupRetiring > 0, generation = it.generation + 1,
            online = OnlineUiState(busy = networkInFlight, phase = if (networkInFlight) "Stopping previous connection…" else "Offline")) }
    }

    override fun onCleared() {
        cleared = true; lock(); if (setupRetiring == 0) setupScope.cancel()
        val pending = preferenceWrite
        preferenceScope.launch { try { pending?.join() } finally { preferenceScope.cancel() } }
    }

    /** Settings alone cannot open a wallet. A matching grant is used only after unlock. */
    fun autoConnectCandidate(node: String?, kind: NodeKind) {
        connectionCandidate = node?.let { it to kind }
        maybeReconnect()
    }

    private fun maybeReconnect() {
        val before = mutable.value
        if (before.receivingBusy) return
        val candidate = connectionCandidate ?: return
        val remembered = rememberedConnection ?: return
        if (before.info == null) return
        if (!canReconnectAfterUnlock(before, appVisible, pendingAuthentication != null, remembered, candidate.first, candidate.second) ||
            networkInFlight || autoConnectEpoch == epoch || session == null) return
        autoConnectEpoch = epoch // One attempt per unlock; failures remain explicitly retryable.
        connect(candidate.first, candidate.second)
    }

    private fun restoreConnectionPreference() {
        val before = mutable.value
        val address = before.info?.address ?: return
        val slot = walletSlot
        val ticket = epoch
        val revision = preferenceRevision
        val pending = preferenceWrite
        viewModelScope.launch {
            try {
                pending?.join()
                val profiles = withContext(Dispatchers.IO) { nodeStore.read() }
                if (ticket != epoch || revision != preferenceRevision || slot != walletSlot) return@launch
                val remembered = profiles?.connections?.find { it.slot == slot && it.address == address }
                rememberedConnection = remembered
                mutable.update { it.copy(online = it.online.copy(backgroundAllowed = remembered?.background ?: it.online.backgroundAllowed,
                    reconnectAfterUnlock = remembered?.reconnect == true)) }
                maybeReconnect()
                val ui = mutable.value
                if (canPollNetwork(ui, pendingAuthentication != null)) syncLoop.start()
                if (ui.stage == WalletStage.Open && ui.online.connected && ui.online.error == null &&
                    ui.online.review == null && !ui.online.pauseRequested && ui.online.status?.let { !needsScanStartChoice(it) } == true)
                    startBackgroundService()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == epoch) mutable.update { it.copy(error = "Connection preferences could not be opened. Enable network access manually to retry.") }
            }
        }
    }

    private fun rememberConnection(reconnect: Boolean, background: Boolean) {
        val before = mutable.value
        val prior = rememberedConnection
        val remembered = if (!reconnect && prior != null) prior.copy(reconnect = false, background = background) else {
            val address = before.info?.address ?: prior?.address ?: session?.details()?.address ?: return
            val node = before.online.node ?: connectionCandidate?.first ?: return
            val kind = before.online.route ?: connectionCandidate?.second ?: return
            RememberedConnection(walletSlot, address, kind, RememberedConnection.fingerprint(node, kind), reconnect, background)
        }
        ++preferenceRevision
        rememberedConnection = remembered
        mutable.update { it.copy(online = it.online.copy(reconnectAfterUnlock = reconnect)) }
        val previous = preferenceWrite
        val revision = preferenceRevision
        preferenceWrite = preferenceScope.launch {
            previous?.join()
            try {
                nodeStore.update { profiles ->
                    // A concurrent saved-node change must never receive an old grant.
                    if (remembered.reconnect) check(profiles.active?.let {
                        remembered.matches(remembered.slot, remembered.address, it, profiles.selected)
                    } == true)
                    profiles.withConnection(remembered)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!cleared && revision == preferenceRevision) {
                        rememberedConnection = remembered.copy(reconnect = false)
                        mutable.update { it.copy(online = it.online.copy(reconnectAfterUnlock = false),
                            error = "The connection preference could not be saved. Check storage and enable network access again.") }
                    }
                }
            }
        }
    }

    fun disableNetworkAccess() {
        rememberConnection(false, mutable.value.online.backgroundAllowed)
        disconnect()
    }

    private fun updateOnline(ticket: Long, change: (OnlineUiState) -> OnlineUiState) {
        if (ticket == networkEpoch && (mutable.value.stage == WalletStage.Open || backgroundLocked))
            mutable.update { it.copy(online = change(it.online)) }
    }

    fun allowBackgroundSync(allowed: Boolean) {
        if (mutable.value.stage != WalletStage.Open || !mutable.value.hasVault) return
        mutable.update { it.copy(online = it.online.copy(backgroundAllowed = allowed), error = null) }
        rememberConnection(mutable.value.online.reconnectAfterUnlock, allowed)
        if (allowed && mutable.value.online.networkEnabled) startBackgroundService()
        if (!allowed) getApplication<Application>().stopService(android.content.Intent(getApplication(), WalletSyncService::class.java))
    }

    private fun startBackgroundService() {
        val ui = mutable.value
        if (!appVisible || !ui.online.backgroundAllowed || ui.online.backgroundService) return
        try { androidx.core.content.ContextCompat.startForegroundService(getApplication(), android.content.Intent(getApplication(), WalletSyncService::class.java).putExtra("connection", connectionId)) }
        catch (_: Exception) {
            mutable.update { it.copy(online = it.online.copy(backgroundAllowed = false),
                error = "Android could not start background sync. Check notifications and system service limits, or keep the wallet open.") }
        }
    }

    internal fun syncServiceStarted(id: Long): Boolean {
        if (id != connectionId || !mutable.value.online.networkEnabled || !mutable.value.online.backgroundAllowed) return false
        mutable.update { it.copy(online = it.online.copy(backgroundService = true)) }
        return true
    }

    internal fun syncServiceStopped(id: Long) {
        if (id != connectionId) return
        mutable.update { it.copy(online = it.online.copy(backgroundService = false)) }
        if (backgroundLocked) lockInternal()
    }

    internal fun syncServiceFailed(id: Long = connectionId) {
        if (id != connectionId) return
        syncServiceStopped(id)
        mutable.update { it.copy(online = it.online.copy(backgroundAllowed = false),
            error = "Background sync is unavailable. Check Wallet sync notifications and system service limits, or keep the wallet open.") }
    }

    internal fun finishBackgroundSync(id: Long) {
        if (id != connectionId) return
        if (backgroundLocked) {
            val completed = backgroundScanFinished(mutable.value.online)
            val error = if (completed) null else mutable.value.online.error ?: "Sync paused. Saved progress was kept; unlock and reconnect to continue."
            lockInternal()
            mutable.update { it.copy(backgroundSyncCompleted = completed, error = error) }
        }
    }

    internal fun stopBackgroundSync(id: Long) {
        if (id != connectionId) return
        rememberConnection(false, mutable.value.online.backgroundAllowed)
        lock()
    }

    internal fun syncServiceTimedOut(id: Long) { if (id == connectionId) lock() }

    private fun onlineWork(phase: String, backgroundScan: Boolean = false, quietPoll: Boolean = false, work: suspend (Long) -> Unit) {
        if (networkInFlight || mutable.value.busy ||
            (mutable.value.stage != WalletStage.Open && !(backgroundScan && backgroundLocked && canContinueBackground(mutable.value, pendingAuthentication != null)))) return
        val ticket = ++networkEpoch
        networkInFlight = true
        updateOnline(ticket) { it.copy(busy = true, polling = quietPoll, phase = if (quietPoll) it.phase else phase, preparation = null, operationStartedAtMs = SystemClock.elapsedRealtime(), message = if (quietPoll) it.message else null, paymentError = false, error = null) }
        networkOperation = viewModelScope.launch {
            try { work(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: OnlineException) {
                if (error is OnlineException.Payment) {
                    updateOnline(ticket) { it.copy(phase = "Payment needs attention", review = null, paymentError = true, error = error.detail) }
                } else if (error is OnlineException.ReviewChanged) {
                    updateOnline(ticket) { it.copy(phase = "Refresh payment", review = null,
                        status = it.status?.copy(synced = false), error = "The chain or review changed. Sync and review the payment again.") }
                } else {
                    val code = if (error is OnlineException.Failed) nodeFailureCode(error.detail) else "NET_UNKNOWN"
                    val preparation = mutable.value.online.preparation
                    // Only enum labels and closed codes; no endpoint, address,
                    // amount, signed bytes or node-supplied strings in logs.
                    android.util.Log.w("WalletNetwork", "operation_failed code=$code stage=${preparation?.stage?.name ?: "NETWORK"} route=${mutable.value.online.route?.name ?: "NONE"}")
                    if (ticket == networkEpoch && error is OnlineException.Closed) {
                        syncLoop.stop()
                        onlineSession?.disconnect(); onlineSession?.destroy(); onlineSession = null
                        updateOnline(ticket) { it.copy(node = null, connected = false, networkEnabled = false, status = it.status?.copy(synced = false)) }
                    }
                    updateOnline(ticket) { it.copy(phase = if (it.connected) "Sync paused" else if (error is OnlineException.Failed) "Connection failed" else "Offline", review = null,
                        status = it.status?.copy(synced = false), error = if (error is OnlineException.Failed) preparationFailure(preparation, error.detail) else "Connection closed. Reconnect to continue.") }
                }
            }
            catch (_: LinkageError) {
                if (ticket == networkEpoch) {
                    syncLoop.stop()
                    onlineSession?.disconnect(); onlineSession?.destroy(); onlineSession = null
                    updateOnline(ticket) { it.copy(networkEnabled = false, connected = false, node = null, phase = "Offline", review = null,
                        status = it.status?.copy(synced = false), error = "The native wallet engine is unavailable.") }
                }
            }
            catch (_: Exception) { updateOnline(ticket) { it.copy(phase = if (it.connected) "Sync paused" else "Offline", review = null,
                status = it.status?.copy(synced = false), error = "The operation stopped. Reconnect and sync to continue.") } }
            finally {
                networkInFlight = false
                updateOnline(ticket) { it.copy(busy = false, polling = false, submitting = false, preparingPayment = false, preparation = null, operationStartedAtMs = null) }
                if (ticket != networkEpoch && onlineSession == null) mutable.update { it.copy(online = it.online.copy(busy = false,
                    phase = if (!it.online.networkEnabled) "Offline" else it.online.phase)) }
                maybeReconnect()
                val ui = mutable.value
                if (canPollNetwork(ui, pendingAuthentication != null)) syncLoop.start()
                if (ui.stage == WalletStage.Open && ui.online.connected && ui.online.error == null &&
                    ui.online.review == null && !ui.online.pauseRequested && ui.online.status?.let { !needsScanStartChoice(it) } == true)
                    startBackgroundService()
            }
        }
    }

    fun nodeSelectionChanged(node: String?, kind: NodeKind) {
        val online = mutable.value.online
        val remembered = rememberedConnection
        if (remembered != null && (node == null || remembered.kind != kind || remembered.nodeId != RememberedConnection.fingerprint(node, kind))) {
            rememberedConnection = null
            ++preferenceRevision
            mutable.update { it.copy(online = it.online.copy(reconnectAfterUnlock = false)) }
        }
        if (online.node != null && (online.node != node || online.route != kind)) disconnect()
    }

    fun disconnect() {
        ++connectionId
        if (backgroundLocked) { lockInternal(); return }
        getApplication<Application>().stopService(android.content.Intent(getApplication(), WalletSyncService::class.java))
        syncLoop.stop()
        ++networkEpoch
        onlineSession?.disconnect(); onlineSession?.destroy(); onlineSession = null
        networkOperation?.cancel()
        mutable.update { it.copy(online = it.online.copy(networkEnabled = false, connected = false,
            busy = networkInFlight, syncing = false, composingPayment = false, preparingPayment = false, preparation = null, submitting = false, polling = false, pauseRequested = false, startedAtMs = null, backgroundService = false,
            phase = if (networkInFlight) "Stopping network…" else "Offline", node = null, route = null,
            review = null, message = null, error = null, status = it.online.status?.copy(synced = false))) }
    }

    fun connect(node: String, kind: NodeKind) {
        if (!mutable.value.hasVault || networkInFlight || mutable.value.busy || mutable.value.receivingBusy || mutable.value.stage != WalletStage.Open) return
        val wallet = session ?: return
        autoConnectEpoch = epoch
        val endpoint = try { checkedNode(kind, node) } catch (error: NodeInputException) {
            mutable.update { it.copy(online = it.online.copy(error = error.problem.message + " [NET_ADDRESS]")) }
            android.util.Log.w("WalletNetwork", "connect_blocked route=${kind.name} code=NET_ADDRESS")
            return
        }
        // GrapheneOS can report a revoked INTERNET permission as a DNS/network
        // failure. Check the app's permission before any native hostname lookup.
        if (getApplication<Application>().checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            disconnect()
            mutable.update { it.copy(online = it.online.copy(phase = "Network permission blocked",
                error = "Allow Network access for VoidKey in this Android profile's app permissions, then retry. [NET_PERMISSION]")) }
            android.util.Log.w("WalletNetwork", "connect_blocked route=${kind.name} code=NET_PERMISSION")
            return
        }
        mutable.update { it.copy(backgroundSyncCompleted = false) }
        if (onlineSession != null && mutable.value.online.node == endpoint && mutable.value.online.route == kind) { refresh(); return }
        disconnect()
        onlineWork(kind.starting) { ticket ->
            updateOnline(ticket) { it.copy(networkEnabled = true, connected = false, node = endpoint, route = kind,
                startedAtMs = SystemClock.elapsedRealtime(), lastBlockAtMs = null) }
            startBackgroundService()
            var opened: OnlineWalletSession? = null
            try {
                withContext(Dispatchers.IO) { opened = wallet.openOnline(endpoint,
                    java.io.File(getApplication<Application>().noBackupFilesDir, "chain-v1").absolutePath, kind.nativeRoute) }
                if (ticket != networkEpoch) return@onlineWork
                val current = checkNotNull(opened)
                check(current.networkRoute() == kind.nativeRoute)
                val cached = withContext(Dispatchers.IO) { current.status() }
                onlineSession = current; opened = null
                updateOnline(ticket) { it.copy(node = endpoint, status = cached) }
                try {
                    if (kind == NodeKind.Direct) {
                        updateOnline(ticket) { it.copy(phase = "Resolving node with Android…") }
                        android.util.Log.i("WalletNetwork", "dns_start resolver=android route=Direct")
                        val addresses = resolveDirectNode(endpoint, kind)
                        if (ticket != networkEpoch || onlineSession !== current) return@onlineWork
                        val ipv6 = addresses.count { ':' in it }
                        android.util.Log.i("WalletNetwork", "dns_ok resolver=android ipv4=${addresses.size - ipv6} ipv6=$ipv6")
                        updateOnline(ticket) { it.copy(phase = "Connecting directly…") }
                        withContext(Dispatchers.IO) { current.connectDirectResolved(addresses) }
                    } else {
                        withContext(Dispatchers.IO) { current.connect() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val detail = if (error is NodeResolutionException) error.problem.detail else null
                    val code = if (detail != null) nodeFailureCode(detail) else if (error is OnlineException.Failed) nodeFailureCode(error.detail) else "NET_UNKNOWN"
                    android.util.Log.w("WalletNetwork", "connect_failed route=${kind.name} code=$code")
                    if (onlineSession === current) { current.disconnect(); current.destroy(); onlineSession = null }
                    if (detail != null) throw OnlineException.Failed(detail)
                    throw error
                }
                if (ticket != networkEpoch || onlineSession !== current) return@onlineWork
                val connectedStatus = withContext(Dispatchers.IO) { current.status() }
                updateOnline(ticket) { it.copy(connected = true, status = connectedStatus) }
                rememberConnection(true, mutable.value.online.backgroundAllowed)
                // A never-scanned zero-height wallet needs an explicit choice:
                // recent blocks for a new wallet, or full history for a restore.
                if (needsScanStartChoice(connectedStatus)) {
                    updateOnline(ticket) { it.copy(phase = "Choose where to start scanning") }
                    getApplication<Application>().stopService(android.content.Intent(getApplication(), WalletSyncService::class.java))
                    return@onlineWork
                }
                syncLoop.start()
                // A scan error (including a future restore height) keeps the
                // established engine available for an explicit rescan.
                syncUntilCaughtUp(current, ticket)
            } finally {
                opened?.disconnect(); opened?.destroy()
                if (ticket == networkEpoch && onlineSession == null) updateOnline(ticket) {
                    it.copy(networkEnabled = false, connected = false, node = null, status = it.status?.copy(synced = false))
                }
            }
        }
    }

    private suspend fun syncUntilCaughtUp(current: OnlineWalletSession, ticket: Long) {
        val started = SystemClock.elapsedRealtime()
        updateOnline(ticket) { it.copy(phase = it.route?.scanning ?: "Syncing…", syncing = true,
            pauseRequested = false, startedAtMs = started, lastBlockAtMs = started, review = null) }
        try {
            while (ticket == networkEpoch && onlineSession === current) {
                android.util.Log.d("WalletNetwork", "sync_batch_started")
                val status = withContext(Dispatchers.IO) { current.syncBatch() }
                android.util.Log.d("WalletNetwork", "sync_batch_completed")
                if (ticket != networkEpoch || onlineSession !== current) return
                val changed = status.nextHeight != mutable.value.online.status?.nextHeight
                updateOnline(ticket) { it.copy(status = status, lastBlockAtMs = if (changed) SystemClock.elapsedRealtime() else it.lastBlockAtMs) }
                if (status.synced) { updateOnline(ticket) { it.copy(phase = it.route?.caughtUp ?: "Synced", pauseRequested = false) }; break }
                if (mutable.value.online.pauseRequested) {
                    updateOnline(ticket) { it.copy(phase = "Sync paused") }
                    break
                }
            }
        } finally {
            updateOnline(ticket) { it.copy(syncing = false) }
            if (ticket == networkEpoch && mutable.value.online.pauseRequested)
                getApplication<Application>().stopService(android.content.Intent(getApplication(), WalletSyncService::class.java))
        }
    }

    fun pauseSync() {
        val before = mutable.value
        if (before.stage != WalletStage.Open || before.busy || pendingAuthentication != null ||
            !before.online.connected || !before.online.syncing) return
        syncLoop.stop()
        mutable.update { it.copy(online = it.online.copy(pauseRequested = true,
            phase = "Pausing after the current batch…")) }
    }

    fun refresh() {
        if (networkInFlight || mutable.value.busy || mutable.value.receivingBusy || mutable.value.stage != WalletStage.Open || pendingAuthentication != null ||
            mutable.value.online.composingPayment || mutable.value.online.submitting || mutable.value.online.review != null) return
        syncLoop.start()
        refreshInternal(true)
    }

    private fun refreshInternal(background: Boolean) {
        val current = onlineSession ?: return
        onlineWork(mutable.value.online.route?.scanning ?: "Syncing…", backgroundScan = true) { ticket ->
            if (background) startBackgroundService()
            syncUntilCaughtUp(current, ticket)
        }
    }

    /** Timers inspect metadata first; a stable tip never enters the scan UI. */
    private fun pollNetwork() {
        if (networkInFlight || !canPollNetwork(mutable.value, pendingAuthentication != null)) return
        val current = onlineSession ?: return
        onlineWork("Checking for updates…", backgroundScan = true, quietPoll = true) { ticket ->
            val status = withContext(Dispatchers.IO) { current.pollUpdates() }
            updateOnline(ticket) { it.copy(status = status) }
            if (!status.synced && ticket == networkEpoch && onlineSession === current)
                syncUntilCaughtUp(current, ticket)
        }
    }

    fun loadReceivingAddresses() = receivingWork { owner, directory -> owner.receivingAddresses(directory) }
    fun createSubaddress(label: String) = receivingWork { owner, directory -> owner.createSubaddress(directory, label) }
    fun selectSubaddress(index: UInt) = receivingWork { owner, directory -> owner.selectSubaddress(directory, index) }
    fun labelSubaddress(index: UInt, label: String) = receivingWork { owner, directory -> owner.labelSubaddress(directory, index, label) }

    private fun receivingWork(work: (OfflineWalletSession, String) -> ReceivingAddressBook) {
        val before = mutable.value
        if (!appVisible || before.stage != WalletStage.Open || !before.hasVault || before.busy || before.receivingBusy ||
            pendingAuthentication != null || before.online.composingPayment || before.online.review != null || before.online.submitting) return
        val owner = session ?: return
        val ticket = epoch
        val directory = java.io.File(getApplication<Application>().noBackupFilesDir, "chain-v1").absolutePath
        syncLoop.stop()
        mutable.update { it.copy(receivingBusy = true, receivingError = null) }
        receivingOperation = viewModelScope.launch {
            try {
                val book = withContext(Dispatchers.IO) { work(owner, directory) }
                if (ticket == epoch && session === owner && mutable.value.stage == WalletStage.Open)
                    mutable.update { it.copy(receiving = book, receivingError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OnlineException.Closed) {
                if (ticket == epoch) {
                    if (onlineSession != null) disconnect()
                    mutable.update { it.copy(receivingError = "The address operation stopped. Unlock or reconnect to reload it. Saved wallet files were kept.") }
                }
            }
            catch (error: OnlineException.Failed) {
                if (ticket == epoch) mutable.update { it.copy(receivingError = error.detail) }
            } catch (_: Exception) {
                if (ticket == epoch) mutable.update { it.copy(receivingError = "Receiving addresses could not be loaded or saved. Your previous addresses were kept.") }
            } finally {
                if (ticket == epoch) {
                    mutable.update { it.copy(receivingBusy = false) }
                    maybeReconnect()
                    if (canPollNetwork(mutable.value, pendingAuthentication != null)) syncLoop.start()
                }
            }
        }
    }

    fun openSendComposer() {
        if (mutable.value.receivingBusy) return
        val ui = mutable.value
        if (ui.stage != WalletStage.Open || !ui.hasVault || ui.info?.mode != KeyWalletMode.SOFTWARE ||
            ui.busy || networkInFlight || pendingAuthentication != null || !ui.online.connected ||
            ui.online.status?.hasScan != true || ui.online.status?.pending != null) return
        syncLoop.stop()
        mutable.update { it.copy(online = it.online.copy(composingPayment = true, message = null, paymentError = false, error = null)) }
    }
    fun closeSendComposer() {
        if (networkInFlight) return
        mutable.update { it.copy(online = it.online.copy(composingPayment = false, paymentError = false,
            phase = if (it.online.status?.synced == true) it.online.route?.caughtUp ?: "Synced" else "Sync paused",
            error = if (it.online.paymentError) null else it.online.error)) }
        if (canPollNetwork(mutable.value, pendingAuthentication != null)) syncLoop.start()
    }

    private suspend fun quotePayment(current: OnlineWalletSession, ticket: Long, destination: String, xmr: String, sendMax: Boolean): Boolean {
        if (!canQuotePayment(mutable.value, appVisible)) return false
        updateOnline(ticket) { it.copy(preparingPayment = true) }
        syncUntilCaughtUp(current, ticket)
        if (ticket != networkEpoch || onlineSession !== current || mutable.value.online.status?.synced != true ||
            !canQuotePayment(mutable.value, appVisible)) return false
        updateOnline(ticket) { it.copy(phase = if (it.route == NodeKind.Tor) "Preparing payment through Tor…" else "Preparing payment…") }
        val review = coroutineScope {
            val monitor = launch {
                while (isActive) {
                    delay(250)
                    if (ticket != networkEpoch || onlineSession !== current) return@launch
                    val progress = try { current.preparationStatus() } catch (_: Exception) { return@launch }
                    updateOnline(ticket) { it.copy(preparation = progress,
                        phase = preparationPhase(progress, it.route) ?: it.phase) }
                }
            }
            try {
                withContext(Dispatchers.IO) { if (sendMax) current.prepareMax(destination.trim()) else current.prepare(destination.trim(), xmr.trim()) }
            } catch (error: OnlineException) {
                val progress = try { current.preparationStatus() } catch (_: Exception) { null }
                updateOnline(ticket) { it.copy(preparation = progress) }
                throw error
            } finally { monitor.cancelAndJoin() }
        }
        updateOnline(ticket) { it.copy(review = review, preparingPayment = false, composingPayment = false, phase = "Review payment") }
        return ticket == networkEpoch && mutable.value.online.review?.reviewId == review.reviewId
    }
    fun prepareSpend(destination: String, xmr: String, sendMax: Boolean = false) {
        if (!canQuotePayment(mutable.value, appVisible) || mutable.value.online.review != null) return
        syncLoop.stop()
        val current = onlineSession ?: return
        onlineWork("Checking latest blocks…") { ticket -> quotePayment(current, ticket, destination, xmr, sendMax) }
    }

    fun discardReview() {
        val current = onlineSession ?: return
        onlineWork(mutable.value.online.route?.caughtUp ?: "Synced") { ticket ->
            withContext(Dispatchers.IO) { current.discardReview() }
            updateOnline(ticket) { it.copy(review = null, composingPayment = false) }
        }
    }

    private fun sendAuthenticated(action: VaultAction) {
        val current = onlineSession ?: return
        val review = mutable.value.online.review
        val pending = mutable.value.online.status?.pending
        syncLoop.stop()
        onlineWork(if (mutable.value.online.route == NodeKind.Tor) "Submitting payment through Tor…" else "Submitting payment…") { ticket ->
            updateOnline(ticket) { it.copy(submitting = true, composingPayment = false) }
            if (!appVisible) background()
            if (ticket != networkEpoch || onlineSession !== current) return@onlineWork
            var updatedReview = false
            var accepted = false
            try {
                if (action == VaultAction.RetrySend) {
                    syncUntilCaughtUp(current, ticket)
                    if (ticket != networkEpoch || onlineSession !== current) return@onlineWork
                    if (mutable.value.online.status?.pending?.transaction != pending?.transaction) {
                        updateOnline(ticket) { it.copy(message = "Payment status updated. Check its confirmation details.", phase = "Payment updated") }
                        return@onlineWork
                    }
                }
                val transaction = withContext(Dispatchers.IO) {
                    if (action == VaultAction.Send) current.confirm(checkNotNull(review).reviewId)
                    else current.retryPending(checkNotNull(pending).transaction)
                }
                accepted = true
                updateOnline(ticket) { it.copy(review = null, phase = "Submitted to node", lastPaymentId = transaction,
                    receiptToOpen = transaction, submission = SubmissionState.Accepted, message = "Submitted to your node. Awaiting a block confirmation.") }
            } catch (changed: OnlineException.ReviewChanged) {
                if (action != VaultAction.Send || review == null || !appVisible || backgroundLocked) throw changed
                updateOnline(ticket) { it.copy(submitting = false) }
                updatedReview = quotePayment(current, ticket, review.destination, review.amountXmr, review.sendMax)
                updateOnline(ticket) { it.copy(message = if (updatedReview)
                    "The chain or review changed. Check the updated amount and fee, then authorize again."
                    else "Payment was not submitted. Open Send again to review it.") }
            } finally {
                val status = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { try { current.status() } catch (_: Exception) { null } }
                updateOnline(ticket) {
                    val committed = status?.pending?.transaction
                    it.copy(review = if (updatedReview) it.review else null, status = status ?: it.status,
                        lastPaymentId = if (!accepted && !updatedReview && committed != null) committed else it.lastPaymentId,
                        submission = if (!accepted && !updatedReview && committed != null) SubmissionState.Unknown else it.submission,
                        receiptToOpen = if (!accepted && !updatedReview && committed != null) committed else it.receiptToOpen)
                }
            }
        }
    }

    fun rescan(height: ULong) {
        val before = mutable.value
        if (height > UInt.MAX_VALUE.toULong() || networkInFlight || before.busy || before.receivingBusy ||
            before.stage != WalletStage.Open || pendingAuthentication != null ||
            !before.online.connected || before.online.review != null || before.online.composingPayment || before.online.submitting) return
        val current = onlineSession ?: return
        onlineWork("Rescanning…") { ticket ->
            val status = withContext(Dispatchers.IO) { current.rescan(height); current.status() }
            updateOnline(ticket) { it.copy(status = status, pauseRequested = false) }
            syncLoop.start()
            startBackgroundService()
            syncUntilCaughtUp(current, ticket)
        }
    }
}
