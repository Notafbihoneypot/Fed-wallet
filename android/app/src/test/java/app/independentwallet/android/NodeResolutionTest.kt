package app.independentwallet.android

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class NodeResolutionTest {
    @Test fun onlyTheSavedHostnameReachesTheResolverAndAnswersStayBounded() = runBlocking {
        var asked: String? = null
        val addresses = resolveDirectNode("https://SELECTED.invalid:18081/json_rpc", NodeKind.Direct) { host ->
            asked = host
            listOf("127.0.0.1", "127.0.0.1", "::1")
        }
        assertEquals("selected.invalid", asked)
        assertEquals(listOf("127.0.0.1", "::1"), addresses)
        assertEquals(32, resolveDirectNode("http://selected.invalid:18081/", NodeKind.Direct) {
            (1..40).map { "127.0.0.$it" }
        }.size)
    }

    @Test fun literalAddressesNeverPerformDns() = runBlocking {
        for ((url, expected) in listOf("http://127.0.0.1:18081/" to "127.0.0.1", "https://[2001:db8::1]:18081/" to "2001:db8::1")) {
            assertEquals(listOf(expected), resolveDirectNode(url, NodeKind.Direct) { error("Unexpected lookup") })
        }
    }

    @Test fun torAndInvalidInputNeverReachSystemDns() = runBlocking {
        val onion = "http://" + "a".repeat(56) + ".onion:18081/"
        for ((node, kind) in listOf(onion to NodeKind.Tor, onion to NodeKind.Direct,
            "https://selected.invalid/" to NodeKind.Tor, "http://user:private-fixture@selected.invalid/" to NodeKind.Direct)) {
            var called = false
            try {
                resolveDirectNode(node, kind) { called = true; emptyList() }
                fail("Expected route/input rejection")
            } catch (_: NodeInputException) { assertFalse(called) }
        }
    }

    @Test fun dnsResponsesHaveDistinctClosedErrors() {
        assertNull(dnsAnswerProblem(0, true))
        for ((code, expected) in listOf(0 to NodeResolutionProblem.NoAddress, 2 to NodeResolutionProblem.Server,
            3 to NodeResolutionProblem.NotFound, 5 to NodeResolutionProblem.Blocked, 15 to NodeResolutionProblem.System)) {
            assertEquals(expected, dnsAnswerProblem(code, false))
            assertNotEquals("NET_UNKNOWN", nodeFailureCode(expected.detail))
        }
        for (problem in NodeResolutionProblem.entries) {
            val error = NodeResolutionException(problem)
            assertEquals(problem.detail, error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains("selected.invalid"))
        }
        assertEquals("[node lookup target hidden]", DirectLookupTarget("private-fixture.invalid", false).toString())
    }

    @Test fun emptyOrNonAddressAnswersCannotBecomeConnectionTargets() = runBlocking {
        for (answers in listOf(emptyList(), listOf(""), listOf("other.invalid"), listOf("127.0.0.1\n"))) {
            try {
                resolveDirectNode("http://selected.invalid:18081/", NodeKind.Direct) { answers }
                fail("Expected unusable address rejection")
            } catch (error: NodeResolutionException) { assertEquals(NodeResolutionProblem.NoAddress, error.problem) }
        }
    }

    @Test fun lookupTimeoutDoesNotBecomeAConnectedState() = runBlocking {
        try {
            resolveDirectNode("http://selected.invalid/", NodeKind.Direct, timeoutMs = 20) { delay(500); listOf("127.0.0.1") }
            fail("Expected bounded lookup timeout")
        } catch (error: NodeResolutionException) { assertEquals(NodeResolutionProblem.Timeout, error.problem) }
    }

    @Test fun ownerCancellationPropagatesInsteadOfBeingRelabelledAsDns() = runBlocking {
        var cleaned = false
        val result = withTimeoutOrNull(20) {
            resolveDirectNode("http://selected.invalid/", NodeKind.Direct) {
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        assertNull(result)
        assertTrue(cleaned)
    }
}
