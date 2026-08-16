package dev.lumenberg.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lumenberg.ai.Account
import dev.lumenberg.ai.AccountStore
import dev.lumenberg.ai.AiClient
import dev.lumenberg.ai.DeviceCode
import dev.lumenberg.ai.GitHubAuth
import dev.lumenberg.ai.Provider
import dev.lumenberg.ai.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the assistant needs to exist: the account, the conversation, the in-flight request.
 * Lives as long as the launcher process so a stray recomposition never drops a reply.
 */
class Session(context: Context, private val scope: CoroutineScope) {
    private val store = AccountStore(context)
    private val client = AiClient()

    /** Every connected account, in the order they were added. */
    var accounts by mutableStateOf(store.load())
        private set
    var active by mutableStateOf(store.active())
        private set
    var models by mutableStateOf(emptyList<String>())
        private set

    val turns = mutableStateListOf<Turn>()
    var streaming by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    /** The account currently answering. */
    val account: Account get() = accounts.getOrNull(active) ?: Account()

    val busy: Boolean get() = streaming != null
    val ready: Boolean get() = account.ready
    val running: Boolean get() = turns.isNotEmpty() || busy || error != null

    /**
     * Verifies a candidate account by listing its models, then keeps it. Connecting a
     * provider that is already connected replaces it rather than adding a duplicate.
     * Returns null on success or a sentence to show the user.
     */
    suspend fun connect(candidate: Account): String? {
        val found = runCatching { client.models(candidate) }
            .getOrElse { return it.message ?: "Could not reach ${candidate.provider.label}." }
        if (found.isEmpty()) return "${candidate.provider.label} did not offer any models for this account."
        val chosen = candidate.model.takeIf { it in found }
            ?: client.preferred(candidate.provider, found)
            ?: found.first()
        val next = candidate.copy(model = chosen)

        val existing = accounts.indexOfFirst { it.provider == next.provider }
        val updated = if (existing >= 0) accounts.toMutableList().apply { set(existing, next) }
        else accounts + next
        val index = if (existing >= 0) existing else updated.lastIndex

        runCatching { store.save(updated, index) }
            .onFailure { return it.message ?: "Could not save that sign-in." }
        accounts = updated
        active = index
        models = found
        return null
    }

    /** Switches which connected account answers the next question. */
    fun use(index: Int) {
        if (index !in accounts.indices || index == active) return
        stop()
        active = index
        models = emptyList()
        runCatching { store.save(accounts, index) }
        loadModels()
    }

    /** Moves to the next connected account, for the swap control in the thread. */
    fun cycle() {
        if (accounts.size > 1) use((active + 1) % accounts.size)
    }

    fun useModel(model: String) {
        val index = active
        val updated = accounts.toMutableList()
        if (index !in updated.indices) return
        updated[index] = updated[index].copy(model = model)
        if (runCatching { store.save(updated, index) }.isSuccess) accounts = updated
    }

    /** Refreshes the model list for the active account, e.g. when opening Settings. */
    fun loadModels() {
        if (!account.ready) return
        val current = account
        scope.launch {
            val found = runCatching { client.models(current) }.getOrNull() ?: return@launch
            if (account == current) models = found
        }
    }

    fun disconnect(index: Int) {
        if (index !in accounts.indices) return
        stop()
        val updated = accounts.toMutableList().apply { removeAt(index) }
        val next = active.coerceAtMost(maxOf(updated.lastIndex, 0))
        runCatching { store.save(updated, next) }
        accounts = updated
        active = next
        models = emptyList()
        if (updated.isEmpty()) clear()
    }

    /**
     * [onOpen] is asked to launch an app by name and answers whether it managed to.
     */
    fun ask(text: String, apps: List<String>, onOpen: (String) -> Boolean) {
        if (text.isBlank() || busy) return
        error = null
        turns += Turn("user", text)
        trim()
        streaming = ""
        val history = turns.toList()
        job = scope.launch {
            val sink = StringBuilder()
            var lastPush = 0L
            runCatching {
                client.send(account, history, apps) { token ->
                    sink.append(token)
                    // One state write per token would be one recomposition per token.
                    // Pushing on a frame budget keeps the text alive and the list still.
                    val now = System.currentTimeMillis()
                    if (now - lastPush >= 50) {
                        lastPush = now
                        val snapshot = sink.toString()
                        withContext(Dispatchers.Main) { streaming = snapshot }
                    }
                }
            }.onSuccess { reply ->
                withContext(Dispatchers.Main) {
                    streaming = null
                    when {
                        reply.open != null && onOpen(reply.open) ->
                            turns += Turn("assistant", "Opening ${reply.open}.")
                        reply.open != null ->
                            error = "There is no app here called \"${reply.open}\"."
                        reply.text.isNotBlank() -> turns += Turn("assistant", reply.text)
                        else -> error = "The assistant returned an empty reply."
                    }
                }
            }.onFailure { failure ->
                // This coroutine may already be cancelled, and the cleanup still has to run.
                withContext(NonCancellable + Dispatchers.Main) {
                    // A stopped reply is still worth keeping; the user watched it arrive.
                    val partial = sink.toString().trim()
                    streaming = null
                    if (failure is CancellationException) {
                        if (partial.isNotEmpty()) turns += Turn("assistant", partial)
                    } else {
                        error = failure.message ?: "That request did not go through."
                    }
                }
            }
        }
    }

    /**
     * Runs GitHub's device flow to the end and keeps the account. Returns null on success
     * or a sentence to show; [onCode] fires as soon as there is a code to display.
     */
    suspend fun signInWithGitHub(onCode: (DeviceCode) -> Unit): String? {
        val auth = GitHubAuth()
        if (!auth.configured) {
            return "This build has no GitHub client id compiled in, so Copilot sign-in is off. " +
                "See the README."
        }
        val code = runCatching { auth.start() }
            .getOrElse { return it.message ?: "Could not reach GitHub." }
        onCode(code)
        val token = runCatching { auth.awaitToken(code) }
            .getOrElse { return it.message ?: "That sign-in did not complete." }
        return connect(Account(provider = Provider.COPILOT, credential = token))
    }

    /** Surfaces a message in the same place replies appear. */
    fun report(message: String) {
        error = message
    }

    fun stop() {
        job?.cancel()
        job = null
        streaming = null
    }

    fun clear() {
        stop()
        turns.clear()
        error = null
    }

    /**
     * Keeps the context small and well-formed: recent turns only, always starting on a
     * user turn, because Anthropic rejects histories that do not.
     */
    private fun trim(keep: Int = 12) {
        while (turns.size > keep) turns.removeAt(0)
        while (turns.isNotEmpty() && turns.first().role != "user") turns.removeAt(0)
    }
}
