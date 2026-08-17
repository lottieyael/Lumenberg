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
import dev.lumenberg.agent.Agent
import dev.lumenberg.agent.AgentService
import dev.lumenberg.agent.Outcome
import dev.lumenberg.agent.Tools

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
    private val tools = Tools(context)

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

    /** Set while a request is being carried out; the text is what the user is shown. */
    var working by mutableStateOf<String?>(null)
        private set

    /** The request a follow-up question belongs to, so an answer resumes it. */
    private var pending: String? = null



    /** The account currently answering. */
    val account: Account get() = accounts.getOrNull(active) ?: Account()

    val busy: Boolean get() = streaming != null || working != null
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
    fun ask(text: String, apps: () -> List<String>, onOpen: (String) -> Boolean) {
        if (text.isBlank() || busy) return
        error = null
        turns += Turn("user", text)
        trim()

        // Every request takes the same path. Most are finished by background lookups that
        // need no permission at all; only the ones that genuinely need an app go near the
        // screen, and only if the user turned that on.
        // An answer to the agent's own question resumes that request rather than starting a
        // new one, which used to strand the model with a bare "the saved one".
        val task = pending?.let { "$it\n\nThe user was asked a question and answered: $text" }
            ?: text
        pending = null
        runTask(task, apps, onOpen)
    }

    /**
     * Carries the request out on the phone. The user sees that something is happening and
     * then the result, never the machinery.
     */
    private fun runTask(task: String, apps: () -> List<String>, onOpen: (String) -> Boolean) {
        working = "Working"
        val agent = Agent(client, tools, apps, onOpen)
        job = scope.launch {
            val outcome = runCatching {
                agent.run(
                    task = task,
                    account = account,
                    onProgress = { where ->
                        scope.launch(Dispatchers.Main) { working = where.ifBlank { "Working" } }
                    },
                    // Asked over whichever app is in front, since the launcher is not.
                    onConfirm = { question ->
                        AgentService.live?.confirm(question) ?: false
                    },
                )
            }.getOrElse { Outcome.Failed(it.message ?: "That did not go through.") }

            withContext(NonCancellable + Dispatchers.Main) {
                working = null
                when (outcome) {
                    is Outcome.Said -> turns += Turn("assistant", outcome.text)
                    is Outcome.Asked -> {
                        pending = task
                        turns += Turn("assistant", outcome.question)
                    }
                    is Outcome.Failed -> error = outcome.reason
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
        working = null
        AgentService.live?.dismissAsk()
    }

    fun clear() {
        stop()
        turns.clear()
        pending = null
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
