package app.independentwallet.android

/** Closed platform codes only; never expose provider text or sensitive exceptions. */
internal fun biometricErrorText(code:Int):String = when(code){
    11 -> "Add a fingerprint in Android security settings, then try again. You can use PIN now."
    12 -> "This device has no supported biometric sensor. Use PIN instead."
    7,9 -> "Biometrics are temporarily locked. Unlock Android with its PIN or password, then try again."
    15 -> "Android requires a security update before strong biometric unlock is available. Use PIN instead."
    1 -> "The biometric sensor is unavailable. Try again or use PIN."
    5,10,13 -> "Biometric authentication was canceled. Use PIN or try again."
    else -> "Biometric authentication could not finish. Use PIN or try again."
}
