package dev.lumenberg.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lumenberg.agent.AgentRuntime
import dev.lumenberg.agent.OpenAppTool
import dev.lumenberg.agent.ToolRisk
import dev.lumenberg.cards.HomeCard
import dev.lumenberg.cards.HomeCardStore
import dev.lumenberg.core.LocalCommand
import dev.lumenberg.core.localCommand
import dev.lumenberg.agent.ToolRegistry
import dev.lumenberg.ai.Account
import dev.lumenberg.ai.AccountStore
import dev.lumenberg.ai.DeviceCode
import dev.lumenberg.ai.GitHubAuth
import dev.lumenberg.ai.ModelClient
import dev.lumenberg.ai.Provider
import dev.lumenberg.ai.Turn
import dev.lumenberg.device.DeviceAccessState
import dev.lumenberg.device.DeviceRuntime
import dev.lumenberg.memory.AgentBackup
import dev.lumenberg.memory.AgentProfile
import dev.lumenberg.memory.AgentProfileStore
import dev.lumenberg.memory.AgentStore
import dev.lumenberg.memory.ForgetTool
import dev.lumenberg.memory.RecallTool
import dev.lumenberg.memory.RememberTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Session(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val agentRoot = File(this.context.filesDir, "agent")
    private val accountStore = AccountStore(this.context)
    private val agentStore = AgentStore(agentRoot)
    private val cardStore = HomeCardStore(agentRoot)
    private val cardJobs = mutableMapOf<String, Job>()
    private val profileStore = AgentProfileStore(agentRoot)
    private val client = ModelClient()
    private val runtime = AgentRuntime(client)
    private val device = DeviceRuntime(this.context)

    var account by mutableStateOf(accountStore.load())
        private set
    var models by mutableStateOf(emptyList<String>())
        private set
    var profile by mutableStateOf(profileStore.load())
        private set

    var cards by mutableStateOf(cardStore.load())
        private set
    var memories by mutableStateOf(agentStore.loadMemories(Int.MAX_VALUE))
        private set
    var refreshingCards by mutableStateOf(emptySet<String>())
        private set
    var cardErrors by mutableStateOf(emptyMap<String, String>())
        private set

    val turns = mutableStateListOf<Turn>().apply { addAll(agentStore.loadTurns()) }
    var streaming by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    val busy: Boolean get() = streaming != null
    val ready: Boolean get() = account.ready
    val active: Boolean get() = turns.isNotEmpty() || busy || error != null
    val avatarFile: File get() = profileStore.avatarFile

    fun pinAnswer(prompt: String, text: String) {
        runCatching { cardStore.pin(prompt, text); cards = cardStore.load() }
            .onFailure { report(it.message ?: "Could not pin that answer.") }
    }

    fun removeCard(id: String) {
        cardJobs.remove(id)?.cancel()
        cardStore.remove(id)
        cards = cardStore.load()
        cardErrors = cardErrors - id
    }

    fun editMemory(id: String, text: String) {
        if (busy) return
        runCatching { agentStore.edit(id, text); reloadMemories() }
            .onFailure { report(it.message ?: "Could not edit memory.") }
    }

    fun deleteMemory(id: String) {
        if (busy) return
        runCatching { agentStore.delete(id); reloadMemories() }
            .onFailure { report(it.message ?: "Could not delete memory.") }
    }

    private fun reloadMemories() { memories = agentStore.loadMemories(Int.MAX_VALUE) }

    fun refreshCard(card: HomeCard) {
        if (card.id in refreshingCards) return
        if (!ready) {
            cardErrors = cardErrors + (card.id to "Connect a model in Settings to refresh this card.")
            return
        }
        refreshingCards = refreshingCards + card.id
        cardErrors = cardErrors - card.id
        val selectedAccount = account
        cardJobs[card.id] = scope.launch {
            try {
                val context = profile.prompt() + "\n" + agentStore.memoryContext() + "\n" + device.promptContext()
                // Refreshes can inspect current facts, but cannot open apps, change memory, or control media.
                val registry = ToolRegistry(listOf(RecallTool(agentStore)) + device.tools().filter { it.spec.risk == ToolRisk.READ })
                val reply = runtime.run(selectedAccount, listOf(Turn("user", card.prompt)), registry,
                    context + "\nRefresh a pinned home card. Return the current answer only. Do not perform actions.") {}
                check(reply.isNotBlank()) { "The model returned an empty answer." }
                cardStore.update(card.id, reply)
                cards = cardStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                cardErrors = cardErrors + (card.id to (failure.message ?: "Refresh failed. Your previous answer is kept."))
            } finally {
                refreshingCards = refreshingCards - card.id
                cardJobs.remove(card.id)
            }
        }
    }

    /** Returns true only for recognized local commands, including a local failure. */
    fun tryLocal(text: String, apps: List<String>, onOpen: (String) -> Boolean): Boolean {
        val command = localCommand(text, apps) ?: return false
        if (busy) return true
        error = null
        when (command) {
            is LocalCommand.Open -> {
                if (!onOpen(command.app)) report("Could not open ${command.app}.")
            }
            is LocalCommand.Ambiguous -> report("More than one app is named ${command.app}. Choose it from the app list.")
            is LocalCommand.Media -> {
                streaming = ""
                job = scope.launch {
                    try {
                        val media = device.controlMedia(command.action)
                        val user = Turn("user", text.trim())
                        val reply = Turn("assistant", "Sent ${command.action} to ${media.app} locally.")
                        turns.addAll(listOf(user, reply))
                        agentStore.appendTurn(user)
                        agentStore.appendTurn(reply)
                        trim()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        report(failure.message ?: "Could not control music.")
                    } finally {
                        streaming = null
                    }
                }
            }
        }
        return true
    }

    fun deviceAccess(): DeviceAccessState = device.accessState()

    suspend fun connect(candidate: Account): String? {
        val found = runCatching { client.models(candidate) }
            .getOrElse { return it.message ?: "Could not reach ${candidate.provider.label}." }
        if (found.isEmpty()) return "${candidate.provider.label} did not offer any models for this account."
        val chosen = candidate.model.takeIf { it in found }
            ?: client.preferred(candidate.provider, found)
            ?: found.first()
        val next = candidate.copy(model = chosen)
        runCatching { accountStore.save(next) }
            .onFailure { return it.message ?: "Could not save that sign-in." }
        models = found
        account = next
        return null
    }

    fun useModel(model: String) {
        val next = account.copy(model = model)
        if (runCatching { accountStore.save(next) }.isSuccess) account = next
    }

    fun loadModels() {
        if (!account.ready) return
        scope.launch { models = runCatching { client.models(account) }.getOrDefault(models) }
    }

    fun signOut() {
        cardJobs.values.toList().forEach { it.cancel() }
        stop()
        accountStore.clear()
        account = Account()
        models = emptyList()
    }

    fun updateProfile(next: AgentProfile) {
        val clean = next.copy(name = next.name.trim().ifBlank { "Lumenberg" })
        profileStore.save(clean)
        profile = clean.copy(hasAvatar = profileStore.avatarFile.exists())
    }

    suspend fun setAvatar(uri: Uri): String? {
        val failure = withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Android could not open that image.")
                input.use { source -> profileStore.avatarFile.outputStream().use(source::copyTo) }
            }.exceptionOrNull()?.message
        }
        if (failure == null) profile = profile.copy(hasAvatar = true)
        return failure
    }

    fun clearAvatar() {
        profileStore.removeAvatar()
        profile = profile.copy(hasAvatar = false)
    }

    suspend fun exportAgent(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Android could not create that backup file.")
            output.use { AgentBackup.export(agentRoot, it) }
        }.exceptionOrNull()?.message
    }

    suspend fun importAgent(uri: Uri): String? {
        job?.cancel()
        job?.join()
        cardJobs.values.toList().forEach { it.cancel() }
        cardJobs.values.toList().forEach { it.join() }
        val failure = withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Android could not open that backup file.")
                input.use { AgentBackup.import(agentRoot, it) }
            }.exceptionOrNull()?.message
        }
        if (failure == null) {
            cards = cardStore.load()
            reloadMemories()
            profile = profileStore.load()
            turns.clear()
            turns.addAll(agentStore.loadTurns())
            error = null
        }
        return failure
    }

    fun ask(text: String, apps: List<String>, onOpen: (String) -> Boolean) {
        if (text.isBlank() || busy) return
        error = null
        val user = Turn("user", text.trim())
        turns += user
        agentStore.appendTurn(user)
        trim()
        streaming = ""
        val history = turns.toList()
        val registry = ToolRegistry(
            listOf(
                OpenAppTool(apps, onOpen),
                RecallTool(agentStore),
                RememberTool(agentStore, sourcePrompt = text.trim()),
                ForgetTool(agentStore),
            ) + device.tools(),
        )
        val stableContext = buildString {
            append(profile.prompt())
            val memories = agentStore.memoryContext()
            if (memories.isNotBlank()) {
                append("\n\nKnown user memory:\n")
                append(memories)
            }
        }

        job = scope.launch {
            val sink = StringBuilder()
            var lastPush = 0L
            runCatching {
                val liveContext = runCatching { device.promptContext() }.getOrDefault("")
                val agentContext = buildString {
                    append(stableContext)
                    if (liveContext.isNotBlank()) {
                        append("\n\n")
                        append(liveContext)
                    }
                }
                runtime.run(account, history, registry, agentContext) { token ->
                    sink.append(token)
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
                    reloadMemories()
                    if (reply.isNotBlank()) {
                        val assistant = Turn("assistant", reply)
                        turns += assistant
                        agentStore.appendTurn(assistant)
                        trim()
                    } else {
                        error = "The assistant returned an empty reply."
                    }
                }
            }.onFailure { failure ->
                withContext(NonCancellable + Dispatchers.Main) {
                    reloadMemories()
                    val partial = sink.toString().trim()
                    streaming = null
                    if (failure is CancellationException) {
                        if (partial.isNotEmpty()) {
                            val assistant = Turn("assistant", partial)
                            turns += assistant
                            agentStore.appendTurn(assistant)
                            trim()
                        }
                    } else {
                        error = failure.message ?: "That request did not go through."
                    }
                }
            }
        }
    }

    suspend fun signInWithGitHub(onCode: (DeviceCode) -> Unit): String? {
        val auth = GitHubAuth()
        if (!auth.configured) {
            return "This build has no GitHub client id compiled in, so Copilot sign-in is off. See the README."
        }
        val code = runCatching { auth.start() }
            .getOrElse { return it.message ?: "Could not reach GitHub." }
        onCode(code)
        val token = runCatching { auth.awaitToken(code) }
            .getOrElse { return it.message ?: "That sign-in did not complete." }
        return connect(Account(provider = Provider.COPILOT, credential = token))
    }

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
        agentStore.clearTurns()
        error = null
    }

    private fun trim(keep: Int = 40) {
        while (turns.size > keep) turns.removeAt(0)
        while (turns.isNotEmpty() && turns.first().role != "user") turns.removeAt(0)
    }
}
