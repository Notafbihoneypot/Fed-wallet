package app.independentwallet.android

/** A consented owner may scan/poll or finish an already approved publication; never create/sign payments. */
internal fun canContinueBackground(wallet: OfflineUiState, authenticationPending: Boolean): Boolean =
    wallet.hasVault && (wallet.stage == WalletStage.Open || (wallet.stage == WalletStage.Locked && wallet.online.backgroundActive)) &&
        !wallet.busy && !authenticationPending && wallet.online.backgroundAllowed &&
        wallet.online.backgroundService && wallet.online.networkEnabled &&
        wallet.online.error == null && !wallet.online.composingPayment && !wallet.online.preparingPayment &&
        (wallet.online.review == null || wallet.online.submitting) &&
        !wallet.online.pauseRequested &&
        (wallet.online.syncing || wallet.online.polling || (wallet.online.submitting && wallet.online.busy) || (!wallet.online.connected && wallet.online.busy) ||
            (wallet.online.connected && !wallet.online.busy && wallet.online.status?.let { !needsScanStartChoice(it) && it.hasScan } == true))

internal fun canPollNetwork(wallet: OfflineUiState, authenticationPending: Boolean): Boolean =
    !wallet.receivingBusy &&
    wallet.hasVault && !wallet.busy && !authenticationPending &&
        (wallet.stage == WalletStage.Open ||
            (wallet.stage == WalletStage.Locked && wallet.online.backgroundActive && canContinueBackground(wallet, false))) &&
        wallet.online.networkEnabled && wallet.online.connected && !wallet.online.pauseRequested &&
        !wallet.online.busy && !wallet.online.composingPayment && !wallet.online.submitting && wallet.online.review == null && wallet.online.error == null &&
        wallet.online.status?.let { !needsScanStartChoice(it) } == true

internal fun canReconnectAfterUnlock(wallet: OfflineUiState, visible: Boolean, authenticationPending: Boolean,
    preference: RememberedConnection, node: String, kind: NodeKind): Boolean =
    visible && wallet.stage == WalletStage.Open && wallet.hasVault && !wallet.busy && !authenticationPending &&
        !wallet.online.networkEnabled && !wallet.online.busy && !wallet.online.composingPayment && !wallet.online.submitting && wallet.online.review == null &&
        wallet.online.error == null && preference.reconnect &&
        wallet.info?.let { preference.matches(wallet.walletSlot, it.address, node, kind) } == true

internal fun backgroundScanFinished(online: OnlineUiState): Boolean =
    !online.busy && syncPresentation(online).phase == SyncPhase.Synced

/** Keeping the visible service alive is separate from permission to do work.
 * Payment review/device authentication pause polling without destroying it. */
internal fun canKeepSyncService(wallet: OfflineUiState): Boolean =
    wallet.hasVault && wallet.online.backgroundAllowed && wallet.online.networkEnabled &&
        !wallet.online.pauseRequested &&
        (wallet.online.busy || wallet.online.status?.let(::needsScanStartChoice) != true) &&
        (wallet.online.error == null || (wallet.online.paymentError && wallet.online.composingPayment)) &&
        (wallet.stage == WalletStage.Open ||
            (wallet.stage == WalletStage.Locked && wallet.online.backgroundActive &&
                canContinueBackground(wallet.copy(busy = false), false)))

/** Batch normal updates; only a durable pending payment needs quicker checks. */
internal fun syncPollIntervalMs(online: OnlineUiState): Long = if (online.status?.pending != null) 60_000L else 5 * 60_000L

internal fun canQuotePayment(wallet: OfflineUiState, visible: Boolean): Boolean =
    visible && wallet.stage == WalletStage.Open && !wallet.busy && !wallet.receivingBusy && !wallet.online.backgroundActive && !wallet.online.submitting
