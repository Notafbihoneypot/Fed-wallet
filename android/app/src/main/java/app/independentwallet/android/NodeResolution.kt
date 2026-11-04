package app.independentwallet.android

import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/** Closed local diagnostics. Neither resolver exception text nor addresses are logged. */
internal enum class NodeResolutionProblem(val detail: String) {
    Permission("Network access was blocked. Allow Network access for this app in this Android profile, then retry. [NET_PERMISSION]"),
    NoNetwork("Android could not reach the network for this lookup. Check this profile's connection, Network permission, VPN and Private DNS settings. [NET_CONNECT]"),
    NotFound("Android DNS reports that this node hostname does not exist. Check the saved hostname, or the DNS service used by this Android profile. [NET_DNS_NXDOMAIN]"),
    NoAddress("Android DNS returned no usable IP address for this node. Check the hostname and this profile's DNS or VPN configuration. [NET_DNS_EMPTY]"),
    Server("The DNS service could not answer this lookup. Check this profile's DNS or VPN connection, then retry. [NET_DNS_SERVER]"),
    Blocked("The DNS service refused this lookup. Check whether this node is blocked by your DNS or VPN settings. [NET_DNS_BLOCKED]"),
    Timeout("The node hostname lookup timed out. Check this profile's connection, DNS or VPN settings, then retry. [NET_TIMEOUT]"),
    System("Android's system resolver could not complete this lookup. Check this profile's connection, DNS or VPN settings. [NET_DNS_SYSTEM]");
}

internal class NodeResolutionException(val problem: NodeResolutionProblem) : Exception(problem.detail)

internal data class DirectLookupTarget(val host: String, val literal: Boolean) {
    override fun toString(): String = "[node lookup target hidden]"
}

internal fun directLookupTarget(node: String, kind: NodeKind): DirectLookupTarget {
    // Tor destinations must never reach the platform resolver, even on error.
    if (kind != NodeKind.Direct) throw NodeInputException(NodeInputProblem.DirectOnion)
    val host = URI(checkedNode(NodeKind.Direct, node)).host
        ?.removeSurrounding("[", "]") ?: throw NodeInputException(NodeInputProblem.Address)
    val literal = ':' in host || host.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))
    return DirectLookupTarget(host, literal)
}

internal fun dnsAnswerProblem(rcode: Int, hasAddresses: Boolean): NodeResolutionProblem? = when {
    rcode == 0 && hasAddresses -> null
    rcode == 0 -> NodeResolutionProblem.NoAddress
    rcode == 3 -> NodeResolutionProblem.NotFound
    rcode == 2 -> NodeResolutionProblem.Server
    rcode == 5 -> NodeResolutionProblem.Blocked
    else -> NodeResolutionProblem.System
}

/** Called only within an explicitly enabled Direct connection; never from saving/settings. */
internal suspend fun resolveDirectNode(
    node: String,
    kind: NodeKind,
    timeoutMs: Long = 15_000,
    lookup: suspend (String) -> List<String> = AndroidNodeDns::lookup,
): List<String> {
    val target = directLookupTarget(node, kind)
    if (target.literal) return listOf(target.host)
    val addresses = withTimeoutOrNull(timeoutMs) { lookup(target.host) }
        ?: throw NodeResolutionException(NodeResolutionProblem.Timeout)
    val bounded = addresses.distinct().take(32)
    if (bounded.isEmpty() || bounded.any { it.isEmpty() || it.length > 45 || it.any { char -> char !in "0123456789abcdefABCDEF:." } })
        throw NodeResolutionException(NodeResolutionProblem.NoAddress)
    // Rust parses every string as an IP address, applies only the URL's port,
    // and keeps the original URL for HTTP Host and certificate authentication.
    return bounded
}
