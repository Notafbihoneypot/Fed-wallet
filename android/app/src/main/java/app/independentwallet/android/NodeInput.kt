package app.independentwallet.android

import app.independentwallet.bindings.PolicyException
import app.independentwallet.bindings.inspectNode
import java.net.URI

enum class NodeProtocol(val prefix: String, val label: String) {
    Http("http://", "HTTP"), Https("https://", "HTTPS");
}

internal fun protocolForNode(value: String?): NodeProtocol? {
    val entered = value?.trim() ?: return null
    return NodeProtocol.entries.firstOrNull { entered.startsWith(it.prefix, ignoreCase = true) }
}

/** Fixed local messages only; never include an entered endpoint or exception text. */
internal enum class NodeInputProblem(val message: String) {
    Empty("Enter your node address."),
    TooLong("The node address is too long (maximum 2048 characters)."),
    Protocol("Include http:// or https:// before the node address. Use the node's RPC port."),
    Characters("Remove spaces, line breaks or non-ASCII characters inside the node URL."),
    Login("Node login is not supported yet. Enter a URL without a username or password."),
    Query("Use the node's base URL without a query or #fragment."),
    Path("Use the node's base URL or its /json_rpc URL. Other URL paths are not supported."),
    Port("Enter a numeric RPC port between 1 and 65535."),
    Ipv6("Put an IPv6 address in square brackets, for example http://[::1]:18081."),
    Address("Check the hostname or IP address and RPC port in the full HTTP/HTTPS URL."),
    DirectOnion("This is an onion node. Enable Use Tor before saving it."),
    TorHttp("This HTTP node needs Direct mode. Turn off Use Tor, or enter an HTTPS or v3 .onion URL.");
}

internal class NodeInputException(val problem: NodeInputProblem) : IllegalArgumentException(problem.message)

/**
 * Human-entry adapter, not a second network parser. Only surrounding whitespace
 * and the exact standard JSON-RPC suffix are removed. A bare host/port uses only
 * the protocol visibly selected in the node form. Rust still validates the
 * final base URL and onion syntax; the chosen scheme, host, port and route stay
 * explicit. This function does no DNS or network I/O.
 */
internal fun checkedNode(kind: NodeKind, value: String, selectedProtocol: NodeProtocol? = null): String {
    fun reject(problem: NodeInputProblem): Nothing = throw NodeInputException(problem)
    if (value.length > NodeProfiles.MAX_NODE) reject(NodeInputProblem.TooLong)
    var entered = value.trim()
    if (entered.isEmpty()) reject(NodeInputProblem.Empty)
    if (entered.any { it.code !in 0x21..0x7e || it == '\\' }) reject(NodeInputProblem.Characters)
    if (!entered.contains("://")) {
        // Do not repair a malformed explicit scheme or a protocol-relative URL.
        // A full HTTPS URL always keeps its scheme even when HTTP is selected.
        if (selectedProtocol == null || entered.startsWith("//") ||
            entered.substringBefore(':').lowercase() in listOf("http", "https", "ftp", "file", "ws", "wss"))
            reject(NodeInputProblem.Protocol)
        entered = selectedProtocol.prefix + entered
        if (entered.length > NodeProfiles.MAX_NODE) reject(NodeInputProblem.TooLong)
    }
    val schemeEnd = entered.indexOf("://")
    if (schemeEnd < 0 || entered.substring(0, schemeEnd).lowercase() !in listOf("http", "https"))
        reject(NodeInputProblem.Protocol)
    val uri = try { URI(entered) } catch (_: Exception) { reject(NodeInputProblem.Address) }
    val authority = uri.rawAuthority ?: reject(NodeInputProblem.Address)
    if ('@' in authority) reject(NodeInputProblem.Login)
    // Do not let URL-parser recovery reinterpret encoded/ambiguous authorities.
    if (authority.isEmpty() || '%' in authority) reject(NodeInputProblem.Address)
    if (uri.rawQuery != null || uri.rawFragment != null) reject(NodeInputProblem.Query)
    if (authority.count { it == ':' } > 1 && !authority.startsWith("[")) reject(NodeInputProblem.Ipv6)
    if (':' in authority && !authority.endsWith("]")) {
        val portText = authority.substringAfterLast(':')
        if (portText.isEmpty() || portText.any { it !in '0'..'9' }) reject(NodeInputProblem.Port)
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) reject(NodeInputProblem.Port)
    }
    val path = uri.rawPath.orEmpty()
    if (path !in listOf("", "/", "/json_rpc", "/json_rpc/")) reject(NodeInputProblem.Path)
    val base = if (path == "/json_rpc" || path == "/json_rpc/") entered.dropLast(path.length) + "/" else entered
    val details = try { inspectNode(base) } catch (_: PolicyException) { reject(NodeInputProblem.Address) }
    if (kind == NodeKind.Direct && details.onion) reject(NodeInputProblem.DirectOnion)
    if (kind == NodeKind.Tor && !details.onion && !details.normalizedUrl.startsWith("https://"))
        reject(NodeInputProblem.TorHttp)
    return details.normalizedUrl
}
