package app.independentwallet.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifetime belongs to the explicit, unlocked connection; never schedules OS work. */
internal class ForegroundSyncLoop(private val scope: CoroutineScope, private val nextTick: suspend () -> Unit,
    private val allowed: () -> Boolean, private val sync: () -> Unit) {
    private var job: Job? = null
    fun start() {
        stop()
        job = scope.launch {
            while (isActive) { nextTick(); if (isActive && allowed()) sync() }
        }
    }
    fun stop() { job?.cancel(); job = null }
}
