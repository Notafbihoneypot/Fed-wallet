package app.independentwallet.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun PaymentQrScanControl(network:String,enabled:Boolean,onActive:(Boolean)->Unit,onRequest:(MoneroPaymentRequest)->Unit){
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var scanning by remember{mutableStateOf(false)}
    var requested by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    var permissionDenied by remember{mutableStateOf(false)}
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRequest by rememberUpdatedState(onRequest)
    val currentActive by rememberUpdatedState(onActive)
    LaunchedEffect(scanning,requested){currentActive(scanning||requested)}
    DisposableEffect(Unit){onDispose{currentActive(false)}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        scanning=granted&&requested&&currentEnabled&&owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        requested=false;permissionDenied=!granted
        if(!granted)message="Allow camera access to scan a receiving address, or paste the address instead."
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){scanning=false;requested=false}
    LaunchedEffect(enabled){if(!enabled){scanning=false;requested=false}}
    OutlinedButton(onClick={
        message=null;permissionDenied=false;currentActive(true)
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanning=true
        else{requested=true;permission.launch(Manifest.permission.CAMERA)}
    },enabled=enabled&&!scanning,modifier=Modifier.fillMaxWidth()){Text("Scan address QR")}
    if(scanning&&enabled){
        LocalQrCamera(onFrame={raw->
            if(scanning&&currentEnabled)try{
                val request=parseMoneroPaymentRequest(raw,network)
                scanning=false;message="QR read. Check the recipient and amount before authorizing payment."
                currentRequest(request)
            }catch(e:IllegalArgumentException){message=e.message?:"Use a Monero receiving-address QR."}
        },onUnavailable={scanning=false;message="The camera is unavailable. Check Android camera access, try again, or paste the address."},
            modifier=Modifier.fillMaxWidth().height(280.dp))
        TextButton(onClick={scanning=false}){Text("Stop camera")}
    }
    message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
    if(permissionDenied)TextButton(onClick={
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
    }){Text("Camera permission settings")}
}
