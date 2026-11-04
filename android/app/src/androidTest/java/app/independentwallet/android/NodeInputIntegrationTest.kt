package app.independentwallet.android

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Public node fixtures only; requires a disposable emulator. No connection is made. */
@RunWith(AndroidJUnit4::class)
class NodeInputIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val original = "https://original.example:18081/"

    @Before fun requireDisposableEmulator() {
        check(context.packageName == "app.independentwallet.android.syncpreview2")
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        NodeStore(context).update { NodeProfiles(direct = original) }
    }

    private fun awaitState(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Expected node state did not arrive", predicate())
    }

    private fun nodes(scenario: ActivityScenario<MainActivity>): WalletViewModel {
        lateinit var vm: WalletViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[WalletViewModel::class.java] }
        awaitState { !vm.state.value.loading }
        assertEquals(original, vm.state.value.savedNode)
        return vm
    }

    @Test fun invalidInputDoesNotInvokeDisconnectionOrReplaceSavedSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = nodes(scenario)
            val revoked = AtomicInteger()
            for (value in listOf("http:node.example:18081", "https://node.example:0", "https://node.example/private-path")) {
                scenario.onActivity {
                    vm.editNode(value)
                    vm.saveNode { revoked.incrementAndGet() }
                }
                assertNotNull(vm.state.value.nodeError)
                assertFalse(vm.state.value.saving)
                assertEquals(original, vm.state.value.savedNode)
                assertEquals(original, NodeStore(context).read()!!.direct)
            }
            assertEquals(0, revoked.get())
        }
    }

    @Test fun editingTheHostBlocksBothStaleEndpointAccessAndSavingWithoutValidation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = nodes(scenario)
            assertEquals(original, vm.state.value.connectionNode)
            scenario.onActivity { vm.editNode("new.example:18081") }
            assertNull(vm.state.value.connectionNode)
            assertEquals(original, NodeStore(context).read()!!.direct)
            scenario.onActivity { vm.selectProtocol(NodeProtocol.Https); vm.saveNode() }
            awaitState { !vm.state.value.saving }
            assertNull(vm.state.value.nodeError)
            assertEquals("https://new.example:18081/", vm.state.value.connectionNode)
            assertEquals("https://new.example:18081/", NodeStore(context).read()!!.direct)
        }
    }

    @Test fun savingTheSameCanonicalNodeDoesNotInvokeDisconnection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = nodes(scenario)
            val revoked = AtomicInteger()
            scenario.onActivity {
                vm.editNode(" \r\nHTTPS://ORIGINAL.example:18081/json_rpc/\t ")
                vm.saveNode { revoked.incrementAndGet() }
            }
            assertNull(vm.state.value.nodeError)
            assertEquals(original, vm.state.value.draftNode)
            assertEquals(original, NodeStore(context).read()!!.direct)
            assertEquals(0, revoked.get())
        }
    }

    @Test fun validChangeRevokesOncePersistsAndSurvivesRecreationOffline() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = nodes(scenario)
            val revoked = AtomicInteger()
            val expected = "http://[2001:db8::1]:18081/"
            scenario.onActivity {
                vm.editNode("\nhttp://[2001:db8::1]:18081/json_rpc\r\n")
                vm.saveNode { revoked.incrementAndGet() }
            }
            awaitState { !vm.state.value.saving }
            assertNull(vm.state.value.nodeError)
            assertEquals(expected, NodeStore(context).read()!!.direct)
            assertEquals(1, revoked.get())
            scenario.recreate()
            lateinit var restored: WalletViewModel
            scenario.onActivity {
                restored = ViewModelProvider(it)[WalletViewModel::class.java]
                assertFalse(it.offlineVm.state.value.online.networkEnabled)
            }
            awaitState { !restored.state.value.loading }
            assertEquals(expected, restored.state.value.savedNode)
        }
    }

    @Test fun oversizedPasteKeepsThePreviousDraftAndReportsTheLimit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = nodes(scenario)
            scenario.onActivity { vm.editNode("x".repeat(NodeProfiles.MAX_NODE + 1)) }
            assertEquals(original, vm.state.value.draftNode)
            assertEquals(NodeInputProblem.TooLong.message, vm.state.value.nodeError)
            assertEquals(original, NodeStore(context).read()!!.direct)
        }
    }
}
