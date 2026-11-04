package app.independentwallet.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ForegroundSyncLoopTest {
    @Test fun explicitStartAndStopOwnThePollingLifetime() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = Channel<Unit>(); var calls = 0
        val loop = ForegroundSyncLoop(scope, { ticks.receive() }, { true }, { calls++ })
        try {
            assertFalse(ticks.trySend(Unit).isSuccess); assertEquals(0, calls)
            loop.start(); assertTrue(ticks.trySend(Unit).isSuccess); assertEquals(1, calls)
            loop.stop(); assertFalse(ticks.trySend(Unit).isSuccess); assertEquals(1, calls)
        } finally { scope.cancel() }
    }
    @Test fun blockedOrBusyWalletsCannotScheduleRefreshes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = Channel<Unit>(); var allowed = false; var calls = 0
        val loop = ForegroundSyncLoop(scope, { ticks.receive() }, { allowed }, { calls++ })
        try {
            loop.start(); assertTrue(ticks.trySend(Unit).isSuccess); assertEquals(0, calls)
            allowed = true; assertTrue(ticks.trySend(Unit).isSuccess); assertEquals(1, calls)
            allowed = false; assertTrue(ticks.trySend(Unit).isSuccess); assertEquals(1, calls)
        } finally { scope.cancel() }
    }
    @Test fun restartingDoesNotLeaveAnOldPollingJobRunning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = Channel<Unit>(); var calls = 0
        val loop = ForegroundSyncLoop(scope, { ticks.receive() }, { true }, { calls++ })
        try {
            loop.start(); loop.start(); assertTrue(ticks.trySend(Unit).isSuccess); assertEquals(1, calls)
            loop.stop(); loop.start(); assertTrue(ticks.trySend(Unit).isSuccess); assertEquals(2, calls)
            loop.stop(); assertFalse(ticks.trySend(Unit).isSuccess)
        } finally { scope.cancel() }
    }
}
