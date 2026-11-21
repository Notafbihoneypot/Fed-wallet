package app.independentwallet.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** What the prompt is for, so a payment is never authorized under a generic "unlock" heading. */
internal fun authenticationTitle(action: VaultAction?): String = when (action) {
    VaultAction.Unlock -> "Unlock wallet"
    VaultAction.Send -> "Authorize payment"
    VaultAction.RetrySend -> "Authorize retry"
    VaultAction.RevealSeed -> "Show recovery phrase"
    VaultAction.RevealKeys -> "Show wallet keys"
    VaultAction.RevealTransactionKey -> "Show transaction key"
    VaultAction.Save -> "Save wallet"
    VaultAction.StartSetup -> "Protect new wallet"
    VaultAction.DiscardSetup -> "Discard setup"
    VaultAction.MainnetCopy -> "Create mainnet copy"
    VaultAction.ConfigureSecurity -> "Security settings"
    VaultAction.EnableBiometric, null -> "Authenticate"
}

@Composable
internal fun AuthenticationDialog(action:VaultAction?,configured:Boolean,busy:Boolean,error:String?,allowBiometric:Boolean,
    submit:(String)->Unit,biometric:()->Unit,device:()->Unit,dismiss:()->Unit){
    var pin by remember{mutableStateOf("")}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){pin=""}
    val ready=!busy&&(!configured||validPin(pin))
    fun confirm(){
        if(!ready)return
        if(configured){val p=pin;pin="";submit(p)}else device()
    }
    AlertDialog(onDismissRequest={if(!busy){pin="";dismiss()}},title={Text(authenticationTitle(action))},
        text={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(configured){
                Text("Enter your app PIN (8 to 12 digits).",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(pin,{if(it.length<=12&&it.all{c->c in '0'..'9'})pin=it},Modifier.fillMaxWidth(),label={Text("App PIN")},
                    singleLine=true,enabled=!busy,isError=error!=null,visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword,autoCorrectEnabled=false,imeAction=ImeAction.Done),
                    keyboardActions=KeyboardActions(onDone={confirm()}))
            }else Text("Confirm with your Android screen lock or a strong biometric.",style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(allowBiometric)OutlinedButton(onClick={pin="";biometric()},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Use biometric")}
        }},
        confirmButton={TextButton(onClick={confirm()},enabled=ready){Text(if(configured)"Unlock"else"Use device PIN / password")}},
        dismissButton={TextButton(onClick={pin="";dismiss()},enabled=!busy){Text("Cancel")}})
}
