package app.independentwallet.android

import app.independentwallet.bindings.WalletPayment
import app.independentwallet.bindings.PreparationStage
import app.independentwallet.bindings.PreparationStatus

fun paymentStatusTitle(payment: WalletPayment, submission: SubmissionState?): String = when {
    payment.minedHeight != null && payment.confirmations > 0uL -> "Confirmed · ${payment.confirmations} ${if (payment.confirmations == 1uL) "confirmation" else "confirmations"}"
    submission == SubmissionState.Accepted -> "Submitted · awaiting confirmation"
    submission == SubmissionState.Unknown -> "Submission reply unknown"
    else -> "Awaiting confirmation"
}

fun paymentPreparationHint(route: NodeKind?): String = if (route == NodeKind.Tor)
    "The first payment may need a larger decoy-data download through Tor. Completed parts are saved for the next attempt. Keep this screen open, or stop preparation and disconnect. Nothing is sent until you review and authorize."
else "Checking the latest blocks, selecting inputs and calculating the fee."

internal fun preparationPhase(status: PreparationStatus, route: NodeKind?): String? {
    val via = if (route == NodeKind.Tor) " through Tor" else ""
    return when (status.stage) {
        PreparationStage.IDLE, PreparationStage.READY -> null
        PreparationStage.CHECKING_CHAIN -> "Checking payment inputs$via…"
        PreparationStage.FETCHING_FEE -> "Getting the network fee$via…"
        PreparationStage.FETCHING_DISTRIBUTION -> "Loading decoy data$via · ${status.cachedBytes / (1024u * 1024u)} MB saved"
        PreparationStage.SELECTING_DECOYS -> "Selecting payment decoys$via…"
        PreparationStage.SIGNING -> "Building payment on this device…"
        PreparationStage.VERIFYING -> "Verifying payment on this device…"
    }
}

internal fun preparationFailure(status: PreparationStatus?, detail: String): String {
    val step = when (status?.stage) {
        PreparationStage.CHECKING_CHAIN -> "Checking payment inputs failed."
        PreparationStage.FETCHING_FEE -> "Getting the network fee failed."
        PreparationStage.FETCHING_DISTRIBUTION -> "Loading decoy data failed. Completed parts were kept for retry."
        PreparationStage.SELECTING_DECOYS -> "Selecting payment decoys failed."
        PreparationStage.SIGNING -> "Building the payment failed."
        PreparationStage.VERIFYING -> "Verifying the payment failed."
        else -> return detail
    }
    return "$step No payment was sent. $detail"
}
