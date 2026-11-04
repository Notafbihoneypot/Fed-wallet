package app.independentwallet.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.independentwallet.bindings.foundationVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WalletUiState(
    val draftNode: String = "",
    val draftName: String = "",
    val addingNode: Boolean = false,
    val draftKind: NodeKind? = null,
    val draftProtocol: NodeProtocol = NodeProtocol.Http,
    val profiles: NodeProfiles = NodeProfiles(),
    val nodeError: String? = null,
    val settingsUnavailable: Boolean = false,
    val coreVersion: String? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
) {
    val savedNode: String? get() = profiles.active
    val nodeKind: NodeKind get() = profiles.selected
    val editorKind: NodeKind get() = draftKind ?: nodeKind
    val hasNodeDraftChanges: Boolean get() = addingNode || draftName != profiles.activeSavedNode?.name.orEmpty() || draftNode != savedNode.orEmpty() ||
        (savedNode != null && protocolForNode(savedNode) != draftProtocol)
    /** New connections cannot use an old saved endpoint while the form is edited. */
    val connectionNode: String? get() = savedNode?.takeIf {
        !loading && !saving && !settingsUnavailable && coreVersion != null && nodeError == null && !hasNodeDraftChanges
    }
}

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NodeStore(application)
    private val mutableState = MutableStateFlow(WalletUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.Default) { foundationVersion() }
                mutableState.update { it.copy(coreVersion = version) }
                val profiles = withContext(Dispatchers.IO) {
                    val saved = store.read() ?: NodeProfiles()
                    saved.copy(direct = saved.direct?.let { checkedNode(NodeKind.Direct, it) },
                        tor = saved.tor?.let { checkedNode(NodeKind.Tor, it) },
                        nodes = saved.nodes?.map { node ->
                            require(checkedNode(node.kind, node.url) == node.url)
                            node
                        })
                }
                mutableState.update { it.copy(profiles = profiles, draftNode = profiles.active.orEmpty(), draftName = profiles.activeSavedNode?.name.orEmpty(),
                    draftProtocol = protocolForNode(profiles.active) ?: NodeProtocol.Http, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: LinkageError) {
                mutableState.update { it.copy(loading = false, nodeError = "The wallet core could not be loaded.") }
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, settingsUnavailable = true,
                    nodeError = "Saved nodes could not be opened. Forget node settings to enter them again.") }
            }
        }
    }

    fun editNode(value: String) {
        if (mutableState.value.saving || mutableState.value.loading) return
        if (value.length > NodeProfiles.MAX_NODE) {
            mutableState.update { it.copy(nodeError = NodeInputProblem.TooLong.message) }
            return
        }
        mutableState.update { it.copy(draftNode = value, draftProtocol = protocolForNode(value) ?: it.draftProtocol, nodeError = null) }
    }

    fun selectProtocol(protocol: NodeProtocol) {
        val before = mutableState.value
        if (before.loading || before.saving || before.settingsUnavailable) return
        val entered = before.draftNode.trim()
        val previous = protocolForNode(entered)
        val next = if (previous == null) before.draftNode else protocol.prefix + entered.drop(previous.prefix.length)
        mutableState.update { it.copy(draftNode = next, draftProtocol = protocol, nodeError = null) }
    }

    private fun changeProfiles(work: () -> NodeProfiles) {
        val before = mutableState.value
        if (before.loading || before.saving || before.coreVersion == null) return
        mutableState.update { it.copy(saving = true, nodeError = null) }
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { work() }
                // Saving/switching preferences never starts Tor or reconnects.
                mutableState.update { it.copy(profiles = next, draftNode = next.active.orEmpty(), draftName = next.activeSavedNode?.name.orEmpty(), addingNode = false, draftKind = null,
                    draftProtocol = protocolForNode(next.active) ?: NodeProtocol.Http, saving = false, settingsUnavailable = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeInputException) {
                mutableState.update { it.copy(saving = false, nodeError = e.problem.message) }
            } catch (_: Exception) {
                mutableState.update { it.copy(saving = false, nodeError = "Node settings could not be saved. Existing settings were kept.") }
            }
        }
    }

    fun editName(value: String) {
        if (mutableState.value.loading || mutableState.value.saving) return
        if (value.length <= 48 && value.none { it.isISOControl() }) mutableState.update { it.copy(draftName = value, nodeError = null) }
    }

    fun startAddingNode() {
        val before = mutableState.value
        if (before.loading || before.saving || before.settingsUnavailable || before.profiles.savedNodes.size >= NodeProfiles.MAX_NODES) return
        mutableState.update { it.copy(addingNode = true, draftKind = before.nodeKind, draftNode = "", draftName = "", nodeError = null) }
    }
    fun cancelAddingNode() {
        mutableState.update { it.copy(addingNode = false, draftKind = null, draftNode = it.savedNode.orEmpty(),
            draftName = it.profiles.activeSavedNode?.name.orEmpty(), draftProtocol = protocolForNode(it.savedNode) ?: NodeProtocol.Http, nodeError = null) }
    }
    fun selectEditorKind(kind: NodeKind) {
        if (mutableState.value.addingNode) mutableState.update { it.copy(draftKind = kind, nodeError = null) }
        else selectNodeKind(kind)
    }
    fun chooseSavedNode(id: String, onChanged: () -> Unit) {
        val before = mutableState.value
        if (before.loading || before.saving || before.settingsUnavailable) return
        val node = before.profiles.savedNodes.find { it.id == id } ?: return
        if (before.savedNode != node.url || before.nodeKind != node.kind) onChanged()
        changeProfiles { store.update { it.chooseNode(id) } }
    }
    fun removeSavedNode(id: String, onChanged: () -> Unit) {
        val before = mutableState.value
        if (before.loading || before.saving || before.settingsUnavailable) return
        if (before.profiles.activeSavedNode?.id == id) onChanged()
        changeProfiles { store.update { it.removeNode(id) } }
    }
    fun saveNode(onValidated: () -> Unit = {}) {
        val before = mutableState.value
        if (before.loading || before.saving || before.settingsUnavailable || before.coreVersion == null) return
        val normalized = try { checkedNode(before.editorKind, before.draftNode, before.draftProtocol) }
        catch (e: NodeInputException) { mutableState.update { it.copy(nodeError = e.problem.message) }; return }
        val replacing = if (before.addingNode) null else before.profiles.activeSavedNode?.id
        val id = RememberedConnection.fingerprint(normalized, before.editorKind)
        val problem = when {
            replacing == null && before.profiles.savedNodes.size >= NodeProfiles.MAX_NODES -> "You can save five nodes. Remove one before adding another."
            before.profiles.savedNodes.any { it.id == id && it.id != replacing } -> "This node and route are already saved. Choose it from your list."
            else -> null
        }
        if (problem != null) { mutableState.update { it.copy(nodeError = problem) }; return }
        if (normalized != before.savedNode || before.editorKind != before.nodeKind) onValidated()
        changeProfiles { store.update { it.withSavedNode(before.editorKind, normalized, before.draftName, replacing) } }
    }

    fun selectNodeKind(kind: NodeKind) {
        if (kind == mutableState.value.nodeKind) return
        changeProfiles { store.update { it.select(kind) } }
    }

    fun clearNode() {
        val kind = mutableState.value.nodeKind
        changeProfiles { store.update { it.withNode(kind, null) } }
    }

    fun forgetUnavailableSettings() {
        if (!mutableState.value.settingsUnavailable) return
        changeProfiles { store.clear(); NodeProfiles() }
    }
}
