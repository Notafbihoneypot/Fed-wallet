package app.independentwallet.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun AuthenticationDialog(configured:Boolean,busy:Boolean,error:String?,allowBiometric:Boolean,
    submit:(String)->Unit,biometric:()->Unit,device:()->Unit,dismiss:()->Unit){
    var pin by remember{mutableStateOf("")}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){pin=""}
    AlertDialog(onDismissRequest={if(!busy){pin="";dismiss()}},title={Text("Authenticate")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(configured){Text("Enter your app PIN")
            OutlinedTextField(pin,{if(it.length<=12&&it.all{c->c in '0'..'9'})pin=it},label={Text("App PIN")},singleLine=true,enabled=!busy,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword,autoCorrectEnabled=false))
        }else Text("Use your Android device PIN, password or a strong biometric.")
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(allowBiometric)TextButton(onClick={pin="";biometric()},enabled=!busy){Text("Use biometric")}
    }},confirmButton={TextButton(onClick={if(configured){val p=pin;pin="";submit(p)}else device()},enabled=!busy&&(!configured||validPin(pin))){Text(if(configured)"Unlock"else"Use device PIN / password")}},
        dismissButton={TextButton(onClick={pin="";dismiss()},enabled=!busy){Text("Cancel")}})
}
