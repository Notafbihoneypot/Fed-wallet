package app.independentwallet.android
import app.independentwallet.bindings.SpendReview
import app.independentwallet.bindings.WalletSyncStatus
import app.independentwallet.bindings.PreparationStatus
enum class SubmissionState { Accepted, Unknown }

data class OnlineUiState(
    val networkEnabled: Boolean = false, val connected: Boolean = false,
    val syncing: Boolean = false, val pauseRequested: Boolean = false,
    val startedAtMs: Long? = null, val lastBlockAtMs: Long? = null,
    val paymentError: Boolean = false, val composingPayment: Boolean = false, val submitting: Boolean = false, val polling: Boolean = false,
    val operationStartedAtMs: Long? = null,
    val preparingPayment: Boolean = false, val preparation: PreparationStatus? = null,
    val lastPaymentId: String? = null, val submission: SubmissionState? = null,
    val receiptToOpen: String? = null,
    val busy: Boolean = false, val phase: String = "Offline", val node: String? = null,
    val route: NodeKind? = null,
    val backgroundAllowed: Boolean = false, val backgroundService: Boolean = false,
    val backgroundActive: Boolean = false,
    val reconnectAfterUnlock: Boolean = false,
    val status: WalletSyncStatus? = null, val review: SpendReview? = null,
    val message: String? = null, val error: String? = null,
)

/** Acknowledging a UI event must retain the payment's durable presentation state. */
internal fun consumeReceiptEvent(state: OnlineUiState, id: String): OnlineUiState =
    if (state.receiptToOpen == id) state.copy(receiptToOpen = null) else state
