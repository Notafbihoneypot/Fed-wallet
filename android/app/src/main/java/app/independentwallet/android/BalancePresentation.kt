package app.independentwallet.android

/** Explain native balances; never turn locked, reserved or stale funds into spendable funds. */
internal fun balanceExplanation(online: OnlineUiState): String? {
    val status = online.status ?: return null
    if (status.viewOnly) return "Spent status is unknown for this viewing wallet."
    if (!online.networkEnabled || !online.connected)
        return "Saved balance. Reconnect to update confirmations and spendable XMR."
    if (online.error != null || !status.synced || online.syncing || online.polling)
        return "Sync to the latest block to update confirmations and spendable XMR."
    status.nextUnlockBlocks?.takeIf { it > 0uL }?.let { blocks ->
        return "Received XMR is waiting for confirmations. Next unlock in $blocks ${if (blocks == 1uL) "block" else "blocks"}."
    }
    if (status.balanceXmr != status.unlockedXmr)
        return "Some received XMR has an additional lock. It is not spendable yet."
    if (status.pending != null) return "Funds for your pending payment remain reserved."
    return null
}
