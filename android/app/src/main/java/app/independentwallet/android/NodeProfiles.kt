package app.independentwallet.android

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import app.independentwallet.bindings.WalletNetworkRoute

enum class NodeKind(val label: String, val nativeRoute: WalletNetworkRoute) {
    Direct("Direct node", WalletNetworkRoute.DIRECT), Tor("Tor node", WalletNetworkRoute.TOR);
    val connectionLabel: String get() = if (this == Direct) "Direct · normal node" else "Tor · private route"
    val starting: String get() = if (this == Direct) "Connecting directly…" else "Starting Tor and connecting…"
    val scanning: String get() = if (this == Direct) "Syncing directly…" else "Syncing through Tor…"
    val caughtUp: String get() = if (this == Direct) "Synced directly" else "Synced through Tor"
}

/** Encrypted user endpoints only. No default node or automatic failover. */
data class SavedNode(val name: String, val url: String, val kind: NodeKind) {
    val id: String get() = RememberedConnection.fingerprint(url, kind)
    init {
        require(name.isNotBlank() && name.length <= 48 && name.none { it.isISOControl() })
        require(url.isNotBlank() && url.toByteArray(Charsets.UTF_8).size <= NodeProfiles.MAX_NODE)
    }
    override fun toString(): String = "[private saved node]"
}

data class NodeProfiles(val selected: NodeKind = NodeKind.Direct, val direct: String? = null, val tor: String? = null,
    val connections: List<RememberedConnection> = emptyList(),
    val nodes: List<SavedNode>? = null, val selectedNodeId: String? = null) {
    // Null means the old one-per-route payload; empty means an explicitly empty list.
    val savedNodes: List<SavedNode> get() = nodes ?: NodeKind.entries.mapNotNull { kind -> node(kind)?.let { SavedNode(kind.label, it, kind) } }
    val active: String? get() = if (nodes == null) node(selected) else nodes.find { it.id == selectedNodeId && it.kind == selected }?.url
    val activeSavedNode: SavedNode? get() = savedNodes.find { it.url == active && it.kind == selected }
    fun node(kind: NodeKind): String? = if (kind == NodeKind.Direct) direct else tor
    private fun revoke(): NodeProfiles = copy(connections = connections.map { it.copy(reconnect = false) })
    fun select(kind: NodeKind): NodeProfiles = if (kind == selected) this else copy(selected = kind,
        selectedNodeId = nodes?.find { it.kind == kind && it.url == node(kind) }?.id).revoke()
    fun withNode(kind: NodeKind, value: String?): NodeProfiles {
        if (nodes != null) return if (value == null) activeSavedNode?.let { removeNode(it.id) } ?: this
            else withSavedNode(kind, value, kind.label, activeSavedNode?.id)
        val next = if (kind == NodeKind.Direct) copy(selected = kind, direct = value) else copy(selected = kind, tor = value)
        return if (kind == selected && value == node(kind)) next else next.revoke()
    }
    fun chooseNode(id: String): NodeProfiles {
        val n = savedNodes.firstOrNull { it.id == id } ?: error("Saved node not found")
        val next = copy(selected = n.kind, nodes = savedNodes, selectedNodeId = n.id,
            direct = if (n.kind == NodeKind.Direct) n.url else direct, tor = if (n.kind == NodeKind.Tor) n.url else tor)
        return if (active == n.url && selected == n.kind) next else next.revoke()
    }
    fun withSavedNode(kind: NodeKind, url: String, name: String, replacingId: String? = null): NodeProfiles {
        val n = SavedNode(name.trim().ifEmpty { kind.label }, url, kind)
        val old = savedNodes
        require(replacingId == null || old.any { it.id == replacingId })
        require(old.none { it.id == n.id && it.id != replacingId })
        require(replacingId != null || old.size < MAX_NODES)
        val replaced = old.find { it.id == replacingId }
        val list = if (replacingId == null) old + n else old.map { if (it.id == replacingId) n else it }
        val next = copy(nodes = list, selectedNodeId = n.id, selected = kind,
            direct = if (kind == NodeKind.Direct) url else if (replaced?.kind == NodeKind.Direct && direct == replaced.url) null else direct,
            tor = if (kind == NodeKind.Tor) url else if (replaced?.kind == NodeKind.Tor && tor == replaced.url) null else tor)
        return if (active == url && selected == kind) next else next.revoke()
    }
    fun removeNode(id: String): NodeProfiles {
        val removed = savedNodes.find { it.id == id } ?: return this
        val next = copy(nodes = savedNodes.filterNot { it.id == id }, selectedNodeId = activeSavedNode?.id?.takeUnless { it == id },
            direct = direct?.takeUnless { removed.kind == NodeKind.Direct && it == removed.url },
            tor = tor?.takeUnless { removed.kind == NodeKind.Tor && it == removed.url },
            connections = connections.map { if (it.nodeId == id) it.copy(reconnect = false) else it })
        return if (activeSavedNode?.id == id) next.revoke() else next
    }
    fun withConnection(connection: RememberedConnection): NodeProfiles = copy(connections = connections.filterNot { it.slot == connection.slot } + connection)
    override fun toString(): String = "[private node settings]"

    internal fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val v5=connections.any{it.slot.ordinal<0}
            out.write(if(v5)MAGIC5 else if (nodes != null) MAGIC4 else if (connections.isEmpty()) MAGIC else MAGIC3); out.writeByte(selected.ordinal)
            for (value in listOf(direct, tor)) {
                val encoded = value?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
                require(value == null || encoded.isNotEmpty()); require(encoded.size <= MAX_NODE)
                out.writeShort(encoded.size); out.write(encoded)
            }
            if (connections.isNotEmpty() || nodes != null || v5) {
                require(connections.size <= WalletSlot.MAX_SLOTS && connections.map { it.slot }.distinct().size == connections.size)
                out.writeByte(connections.size)
                for (c in connections) {
                    require(c.address.length in 1..196 && c.address.all { it.code in 33..126 }); require(c.nodeId.matches(Regex("[0-9a-f]{64}")))
                    if(v5)out.writeUTF(c.slot.name)else out.writeByte(c.slot.ordinal); out.writeUTF(c.address); out.writeByte(c.kind.ordinal); out.writeUTF(c.nodeId)
                    out.writeByte((if (c.reconnect) 1 else 0) or (if (c.background) 2 else 0))
                }
            }
            (if(v5)savedNodes else nodes)?.let { list ->
                val selectedId=if(v5&&nodes==null)activeSavedNode?.id else selectedNodeId
                validateList(list, selectedId, selected, direct, tor)
                out.writeByte(list.size); out.writeUTF(selectedId.orEmpty())
                for (n in list) { out.writeUTF(n.name); out.writeByte(n.kind.ordinal); out.writeUTF(n.url) }
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    companion object {
        private val MAGIC = byteArrayOf(0x49, 0x57, 0x4e, 0x32)
        private val MAGIC3 = byteArrayOf(0x49, 0x57, 0x4e, 0x33)
        private val MAGIC4 = byteArrayOf(0x49, 0x57, 0x4e, 0x34)
        private val MAGIC5 = byteArrayOf(0x49, 0x57, 0x4e, 0x35)
        const val MAX_NODES = 5
        const val MAX_NODE = 2048
        const val MAX_BYTES = 65536
        private fun validateList(list: List<SavedNode>, id: String?, selected: NodeKind, direct: String?, tor: String?) {
            require(list.size <= MAX_NODES && list.map { it.id }.distinct().size == list.size)
            require(id == null || list.any { it.id == id && it.kind == selected && it.url == if (selected == NodeKind.Direct) direct else tor })
            require(direct == null || list.any { it.kind == NodeKind.Direct && it.url == direct })
            require(tor == null || list.any { it.kind == NodeKind.Tor && it.url == tor })
        }
        private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        internal fun decode(bytes: ByteArray): NodeProfiles {
            require(bytes.size in 1..MAX_BYTES)
            val magic = bytes.take(4).toByteArray(); val v3 = magic.contentEquals(MAGIC3); val v4 = magic.contentEquals(MAGIC4); val v5=magic.contentEquals(MAGIC5)
            if (!v3 && !v4 && !v5 && !magic.contentEquals(MAGIC)) {
                require(bytes.size <= MAX_NODE); val value = utf8(bytes); val uri = URI(value)
                require(uri.scheme in listOf("http", "https") && uri.host != null)
                return NodeProfiles().withNode(if (uri.host.endsWith(".onion")) NodeKind.Tor else NodeKind.Direct, value)
            }
            return DataInputStream(bytes.inputStream()).use { input ->
                input.skipBytes(4); val selected = NodeKind.entries.getOrNull(input.readUnsignedByte()) ?: error("Unsupported selection")
                val urls = List(2) {
                    val size = input.readUnsignedShort(); require(size <= MAX_NODE && size <= input.available())
                    if (size == 0) null else utf8(ByteArray(size).also { input.readFully(it) })
                }
                val connections = if (!v3 && !v4 && !v5) emptyList() else {
                    val count = input.readUnsignedByte(); require(count in (if (v4||v5) 0 else 1)..(if(v5)WalletSlot.MAX_SLOTS else WalletSlot.entries.size))
                    List(count) {
                        val slot = if(v5)WalletSlot.fromId(input.readUTF())else WalletSlot.entries.getOrNull(input.readUnsignedByte()) ?: error("Unsupported wallet")
                        val address = input.readUTF(); require(address.length in 1..196 && address.all { it.code in 33..126 })
                        val kind = NodeKind.entries.getOrNull(input.readUnsignedByte()) ?: error("Unsupported route")
                        val id = input.readUTF(); require(id.matches(Regex("[0-9a-f]{64}")))
                        val flags = input.readUnsignedByte(); require(flags in 0..3)
                        RememberedConnection(slot, address, kind, id, flags and 1 != 0, flags and 2 != 0)
                    }.also { require(it.map { c -> c.slot }.distinct().size == it.size) }
                }
                var id: String? = null
                val list = if (!v4&&!v5) null else {
                    val count = input.readUnsignedByte(); require(count in 0..MAX_NODES); id = input.readUTF().ifEmpty { null }
                    List(count) {
                        val name = input.readUTF(); val kind = NodeKind.entries.getOrNull(input.readUnsignedByte()) ?: error("Unsupported route")
                        SavedNode(name, input.readUTF(), kind)
                    }.also { validateList(it, id, selected, urls[0], urls[1]) }
                }
                require(input.available() == 0)
                NodeProfiles(selected, urls[0], urls[1], connections, list, id)
            }
        }
    }
}

data class RememberedConnection(val slot: WalletSlot, val address: String, val kind: NodeKind,
    val nodeId: String, val reconnect: Boolean, val background: Boolean) {
    fun matches(slot: WalletSlot, address: String, node: String, kind: NodeKind): Boolean =
        this.slot == slot && this.address == address && this.kind == kind && nodeId == fingerprint(node, kind)
    override fun toString(): String = "[private connection preference]"
    companion object {
        fun fingerprint(node: String, kind: NodeKind): String = MessageDigest.getInstance("SHA-256")
            .digest((kind.name + "\n" + node).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
