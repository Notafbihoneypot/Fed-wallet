package app.independentwallet.android

/** Return only a closed diagnostic code; never copy node/OS input into logs. */
internal fun nodeFailureCode(detail: String): String {
    val code = detail.substringAfterLast('[', "").substringBefore(']')
    return if (code in setOf("NET_ADDRESS", "NET_PERMISSION", "NET_DNS", "NET_REFUSED",
        "NET_CONNECT", "NET_TIMEOUT", "NET_TLS", "NET_TOR_START", "NET_TOR_NODE",
        "NET_HTTP", "NET_STATUS", "NET_ENCODING", "NET_SIZE", "NET_CANCELLED", "NODE_RPC",
        "NET_DNS_NXDOMAIN", "NET_DNS_EMPTY", "NET_DNS_SERVER", "NET_DNS_BLOCKED", "NET_DNS_SYSTEM", "NET_NETWORK")) code
        else "NET_UNKNOWN"
}
