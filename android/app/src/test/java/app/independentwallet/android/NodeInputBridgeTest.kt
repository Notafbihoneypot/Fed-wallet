package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test

class NodeInputBridgeTest {
    private fun rejected(value: String, problem: NodeInputProblem, kind: NodeKind = NodeKind.Direct) {
        val error = assertThrows(NodeInputException::class.java) { checkedNode(kind, value) }
        assertEquals(problem, error.problem)
        assertEquals(problem.message, error.message)
        assertNull(error.cause)
    }

    @Test fun schemeAndPortsAreNeverGuessedOrDowngraded() {
        for (value in listOf("node.example:18081", "//node.example:18081", "ftp://node.example:18081", "http:node.example:18081"))
            rejected(value, NodeInputProblem.Protocol)
        for (port in listOf("", "0", "-1", "+18081", "65536", "abc", "9999999999999999999"))
            rejected("https://node.example:$port", NodeInputProblem.Port)
    }

    @Test fun onlyTheExactStandardRpcSuffixIsRemoved() {
        for (path in listOf("/rpc", "/prefix/json_rpc", "/json_rpc/other", "/JSON_RPC", "/%6ason_rpc", "/./json_rpc", "/json_rpc/..", "//json_rpc"))
            rejected("https://node.example:18081$path", NodeInputProblem.Path)
        for (suffix in listOf("?secret=private-node-fixture", "#private-node-fixture", "/json_rpc?node=other"))
            rejected("https://node.example:18081$suffix", NodeInputProblem.Query)
    }

    @Test fun ambiguousAuthoritiesAndInlineLoginsAreRejected() {
        for (authority in listOf("user:private-node-fixture@node.example", "@node.example", "node.example@other.example"))
            rejected("http://$authority:18081", NodeInputProblem.Login)
        for (value in listOf("http://%6eode.example:18081", "http:///node.example", "http://-bad.example:18081", "http://example..com:18081", "http://short.onion:18081"))
            rejected(value, NodeInputProblem.Address)
        rejected("http://2001:db8::1:18081", NodeInputProblem.Ipv6)
    }

    @Test fun interiorWhitespaceControlsAndUnicodeAreNeverCleanedIntoAnotherHost() {
        for (separator in listOf(" ", "\n", "\r", "\t", "\u0000", "\u00a0", "\u200b", "\\"))
            rejected("http://no${separator}de.example:18081", NodeInputProblem.Characters)
        rejected("https://nöde.example:18081", NodeInputProblem.Characters)
        rejected(" \r\n\t ", NodeInputProblem.Empty)
        rejected(" ".repeat(NodeProfiles.MAX_NODE) + "https://node.example", NodeInputProblem.TooLong)
    }

    @Test fun routeMismatchHasActionableErrorsWithoutChangingTheChosenRoute() {
        val onion = "http://" + "a".repeat(56) + ".onion:18081/json_rpc"
        rejected(onion, NodeInputProblem.DirectOnion)
        rejected("http://node.example:18081/json_rpc", NodeInputProblem.TorHttp, NodeKind.Tor)
    }

    @Test fun validationMessagesNeverEchoPrivateInput() {
        for (value in listOf("http://user:private-node-fixture@node.example:18081", "https://private-node-fixture.example/secret-path", "http://private-node-fixture.example:bad", "http://private-node-fixture.example?token=secret")) {
            val error = assertThrows(NodeInputException::class.java) { checkedNode(NodeKind.Direct, value) }
            assertFalse(error.message!!.contains("private-node-fixture"))
            assertFalse(error.message!!.contains("secret-path"))
            assertFalse(error.toString().contains(value))
        }
    }
}
