package app.independentwallet.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.independentwallet.bindings.KeyWalletMode

@Composable fun WalletSettingsPanel(state:OfflineUiState,vm:OfflineWalletViewModel,authenticate:(VaultAction)->Unit,version:String?,wallets:()->Unit,reveal:(VaultAction)->Unit){
    var pinDialog by remember{mutableStateOf(false)};var duressDialog by remember{mutableStateOf(false)};var rename by remember{mutableStateOf(false)};var archived by remember{mutableStateOf(false)}
    val idle=!state.busy&&!state.online.busy&&!state.receivingBusy;val managed=state.securityAuthorized&&idle
    state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    Text("Wallets",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    OutlinedButton(onClick=wallets,enabled=idle,modifier=Modifier.fillMaxWidth()){Text("Manage wallets")}
    TextButton(onClick={rename=true},enabled=idle){Text("Rename ${state.walletName}")}
    Text("Backup & keys",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    Text("Authenticate again to view recovery material. Keep it offline and private.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={reveal(VaultAction.RevealSeed)},enabled=idle&&state.stage==WalletStage.Open&&state.hasVault&&state.info?.seedFormat!=null,modifier=Modifier.fillMaxWidth()){Text("View recovery phrase")}
    OutlinedButton(onClick={reveal(VaultAction.RevealKeys)},enabled=idle&&state.stage==WalletStage.Open&&state.hasVault,modifier=Modifier.fillMaxWidth()){Text("View wallet keys")}
    if(state.info?.mode==KeyWalletMode.KEYSTONE)Text("The seed and private spending key stay on Keystone. This app only holds its viewing keys.",style=MaterialTheme.typography.bodySmall)
    if(state.stage==WalletStage.Locked)TextButton(onClick={authenticate(VaultAction.Unlock)},enabled=idle){Text("Unlock wallet for backup & keys")}
    HorizontalDivider()
    Text("App lock",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    Text(if(state.security.pin)"App PIN enabled · biometric unlock ${if(state.security.biometrics)"enabled"else"off"}"else"Android device PIN/password or strong biometric")
    state.security.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    if(state.security.migrating)Text("PIN setup needs to finish rewrapping existing wallets. Authenticate to continue; saved data is preserved.",style=MaterialTheme.typography.bodySmall)
    val pauseSetup=state.hasSetup&&!state.hasVault&&state.stage==WalletStage.Open
    OutlinedButton(onClick={if(pauseSetup)vm.lock()else authenticate(VaultAction.ConfigureSecurity)},enabled=idle,modifier=Modifier.fillMaxWidth()){
        Text(if(pauseSetup)"Pause unfinished setup to change security"else if(managed)"Authenticate again"else"Unlock security settings")
    }
    if(managed){
        Button(onClick={pinDialog=true},modifier=Modifier.fillMaxWidth()){Text(if(state.security.pin)"Change app PIN"else"Set app PIN")}
        if(state.security.pin&&!state.security.migrating){
            if(state.security.biometrics)TextButton(onClick=vm::disableBiometrics){Text("Disable biometric unlock")}
            else OutlinedButton(onClick={authenticate(VaultAction.EnableBiometric)},modifier=Modifier.fillMaxWidth()){Text("Enable biometric unlock")}
            Text("PIN works independently of biometric unlock. Security changes require your normal app PIN.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider();Text("Duress PIN",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text(if(state.security.duress)"Enabled"else"Off")
            Text("Entering this separate PIN immediately erases every wallet, key, node and setting in this app. It does not erase your phone, blockchain records or external backups.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={duressDialog=true},modifier=Modifier.fillMaxWidth()){Text(if(state.security.duress)"Change duress PIN"else"Set duress PIN")}
            if(state.security.duress)TextButton(onClick=vm::disableDuressPin){Text("Disable duress PIN")}
        }
    }
    Text("Screenshots blocked · locks after 5 minutes · device-only storage",style=MaterialTheme.typography.bodySmall)
    val old=state.wallets.filter{it.archived||it.network=="unknown"}
    if(old.isNotEmpty()){
        TextButton(onClick={archived=!archived}){Text("Earlier / test wallets (${old.size})")}
        if(archived)old.forEach{e->TextButton(onClick={vm.selectWalletSlot(e.slot)},enabled=idle){Text(e.name+if(e.archived)" · Stagenet archive"else" · identify saved wallet")}}
    }
    Text("Independent Preview 2 · ${version?:"Core unavailable"}",style=MaterialTheme.typography.labelSmall)
    if(pinDialog)PinSetupDialog(false,state.busy,{pinDialog=false}){p,r,_->pinDialog=false;vm.setAppPin(p,r)}
    if(duressDialog)PinSetupDialog(true,state.busy,{duressDialog=false}){p,r,c->duressDialog=false;vm.setDuressPin(p,r,c)}
    if(rename)NameDialog("Rename wallet",state.walletName,{rename=false}){rename=false;vm.renameWallet(it)}
}
@Composable internal fun WalletPickerDialog(state:OfflineUiState,vm:OfflineWalletViewModel,dismiss:()->Unit){
    var add by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=dismiss,title={Text("Your wallets")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        val list=state.wallets.filter{!it.archived&&it.network=="mainnet"}
        if(list.isEmpty())Text("Add a Mainnet wallet to get started.")
        LazyColumn(Modifier.heightIn(max=360.dp)){items(list,key={it.id}){e->TextButton(onClick={dismiss();vm.selectWalletSlot(e.slot)},enabled=!state.busy){Text(e.name+if(e.slot==state.walletSlot)" · Selected"else"")}}}
        Text("Each wallet has its own recovery material and payment records. Switching locks the current wallet.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={add=true},enabled=!state.busy&&state.wallets.count{!it.archived}<WalletSlot.MAX_WALLETS){Text("Add wallet")}},dismissButton={TextButton(onClick=dismiss){Text("Done")}})
    if(add)NameDialog("New wallet","",{add=false}){add=false;dismiss();vm.addWallet(it)}
}
@Composable private fun NameDialog(title:String,initial:String,dismiss:()->Unit,save:(String)->Unit){
    var value by remember{mutableStateOf(initial)}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={OutlinedTextField(value,{if(it.length<=128)value=it},label={Text("Wallet name")},singleLine=true)},
        confirmButton={TextButton(onClick={save(value)},enabled=(initial.isEmpty()&&value.isEmpty())||validWalletName(value)){Text("Continue")}},dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})
}
@Composable private fun PinSetupDialog(duress:Boolean,busy:Boolean,dismiss:()->Unit,save:(String,String,Boolean)->Unit){
    var pin by remember{mutableStateOf("")};var repeat by remember{mutableStateOf("")};var consent by remember{mutableStateOf(false)}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){pin="";repeat="";consent=false}
    AlertDialog(onDismissRequest={pin="";repeat="";dismiss()},title={Text(if(duress)"Set duress PIN"else"Set app PIN")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(if(duress)"Choose a different 8–12 digit PIN. Entering it in this app's PIN prompt immediately deletes ALL local wallets and app data, with no further confirmation."else"Choose an 8–12 digit PIN. Keep a safe recovery-phrase backup before changing wallet protection.")
            OutlinedTextField(pin,{if(it.length<=12&&it.all{c->c in '0'..'9'})pin=it},label={Text("PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword,autoCorrectEnabled=false))
            OutlinedTextField(repeat,{if(it.length<=12&&it.all{c->c in '0'..'9'})repeat=it},label={Text("Repeat PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword,autoCorrectEnabled=false))
            if(duress){Text("External seed/key copies can still recover on-chain funds. Copies outside this app and other apps are not erased. Pending-payment records are erased too.")
                Row{Checkbox(consent,{consent=it});Text("I have backups and understand this PIN erases every wallet in this app.",style=MaterialTheme.typography.bodySmall)}
            }
        }
    },confirmButton={TextButton(onClick={val p=pin;val r=repeat;pin="";repeat="";save(p,r,consent)},enabled=!busy&&validPin(pin)&&pin==repeat&&(!duress||consent)){Text(if(duress)"Enable erase PIN"else"Save PIN")}},dismissButton={TextButton(onClick={pin="";repeat="";dismiss()}){Text("Cancel")}})
}
@Composable fun WalletKeysPanel(state:OfflineUiState,vm:OfflineWalletViewModel){
    Text("Wallet keys",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
    Text("Private spending keys control your funds. Private view keys reveal transaction activity. Keep these offline.")
    state.keys?.fields()?.forEach{(name,value)->Text(name,style=MaterialTheme.typography.labelLarge);SelectionContainer{Text(value,style=MaterialTheme.typography.bodySmall)};TextButton(onClick={vm.copyWalletKey(name)}){Text("Copy $name")}}
    state.clipboardMessage?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
    TextButton(onClick=vm::clearSeedClipboard){Text("Clear copy")}
    Button(onClick=vm::hideRecovery,modifier=Modifier.fillMaxWidth()){Text("Done")}
}
