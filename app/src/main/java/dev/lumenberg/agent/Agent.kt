package dev.lumenberg.agent

import dev.lumenberg.ai.Account
import dev.lumenberg.ai.AiClient
import dev.lumenberg.ai.AiError
import dev.lumenberg.ai.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What the model asked for this turn. */
private data class Step(val verb: String, val json: JSONObject) {
    fun str(key: String): String = if (json.isNull(key)) "" else json.optString(key)
    fun int(key: String): Int = json.optInt(key, -1)
}

/** What the run produced. */
sealed interface Outcome {
    data class Said(val text: String) : Outcome
    data class Asked(val question: String) : Outcome
    data class Failed(val reason: String) : Outcome
}

/**
 * Runs a task on the phone.
 *
 * The shape is deliberately dull: look at the screen, decide one thing, do it, look again.
 *
 * History is append-only and the app list is sorted rather than left in most-used order,
 * so the prefix is identical from one step to the next and from one run to the next. That
 * is what a provider's cache measures against; rewriting history to tidy it would reset
 * the cache on every step. Whether a cache hit actually happens is the provider's call,
 * not something this code can claim.
 */
class Agent(
    private val client: AiClient,
    private val tools: Tools,
    private val apps: () -> List<String>,
    private val launch: (String) -> Boolean,
) {

    suspend fun run(
        task: String,
        account: Account,
        onProgress: (String) -> Unit,
        onConfirm: suspend (String) -> Boolean,
    ): Outcome {
        // A run no longer needs permission to touch the screen. Most requests are answered
        // by the tools alone, in the background, and the screen verbs simply are not offered
        // when the service is off.
        val service = AgentService.live
        val history = mutableListOf(Turn("user", "Task: $task"))
        var screen: Screen? = null
        var pending: String? = null
        var malformed = 0

        repeat(MAX_STEPS) {
            // The first turn deliberately carries no screen. A question that needs no apps
            // is then answered in one cheap call, without walking an accessibility tree.
            val observation = pending ?: screen?.describe() ?: "Nothing has been looked at yet."
            val reply = runCatching {
                client.converse(account, system(), history + Turn("user", observation))
            }.getOrElse { return Outcome.Failed(plain(it)) }

            history += Turn("user", observation)
            history += Turn("assistant", reply)
            pending = null

            val step = parse(reply)
            if (step == null) {
                // One bad reply should not throw away a run; the model is told and retries.
                if (++malformed > MAX_MALFORMED) {
                    return Outcome.Failed("I could not follow what the assistant asked for.")
                }
                history += Turn("user", "That was not one JSON object. Reply with exactly one.")
                return@repeat
            }
            malformed = 0

            suspend fun observe() {
                val hands = service ?: return
                screen = withContext(Dispatchers.Default) { hands.screen() }
            }

            // Anything the tools can answer happens in the background, with no window and
            // no permission, so it is tried first.
            val answered = runCatching { tools.run(step.verb, step.json) }
                .getOrElse { "That lookup failed." }
            if (answered != null) {
                onProgress(working(step.verb))
                pending = answered
                return@repeat
            }

            // Finishing needs no permission, so these are handled before the check below.
            // Putting the check first meant a run with the phone's apps switched off could
            // never end: "say" was swallowed and every step was spent on the same refusal.
            when (step.verb) {
                "say" -> return Outcome.Said(step.str("text").ifBlank { "Done." })
                "ask" -> return Outcome.Asked(step.str("text").ifBlank { "Which one?" })
            }

            val hands = service
            if (hands == null) {
                history += Turn(
                    "user",
                    "Using apps on the phone is switched off, so the lookups are all that is " +
                        "available. Answer with what you have, using \"say\".",
                )
                return@repeat
            }

            when (step.verb) {

                "open" -> {
                    val name = step.str("app")
                    onProgress(name)
                    if (!launch(name)) {
                        history += Turn("user", "There is no app called \"$name\" on this phone.")
                    }
                    delay(OPENING)
                    observe()
                }

                "tap" -> {
                    val element = (screen?.elements ?: emptyList()).getOrNull(step.int("ref"))
                    if (element == null) {
                        history += Turn("user", "There is no control with that number.")
                        return@repeat
                    }
                    // The label is read again at the moment of asking, so the question
                    // describes the button that is actually about to be pressed.
                    val label = hands.labelNow(element) ?: element.label
                    if (Risk.consequential(label) && !onConfirm(Risk.describe(label))) {
                        return Outcome.Said("Left that alone.")
                    }
                    when (val result = hands.tap(element)) {
                        is TapResult.Done -> Unit
                        is TapResult.Gone ->
                            history += Turn("user", "That control is no longer on the screen.")
                        is TapResult.Changed ->
                            history += Turn(
                                "user",
                                "The screen moved and that spot now reads \"${result.nowReads}\", " +
                                    "so nothing was pressed.",
                            )
                    }
                    observe()
                }

                "type" -> {
                    val element = (screen?.elements ?: emptyList()).getOrNull(step.int("ref"))
                    if (element == null || !hands.type(element, step.str("text"))) {
                        history += Turn("user", "That field could not be typed into.")
                    }
                    delay(SHORT)
                    observe()
                }

                "enter" -> {
                    if (!hands.enter()) history += Turn("user", "There was nothing to submit.")
                    delay(OPENING)
                    observe()
                }

                "scroll" -> {
                    hands.scroll(step.str("direction") != "up")
                    observe()
                }

                "back" -> {
                    hands.back()
                    delay(SHORT)
                    observe()
                }

                "home" -> {
                    hands.home()
                    delay(SHORT)
                    observe()
                }

                else -> history += Turn("user", "\"${step.verb}\" is not something you can do.")
            }
        }
        return Outcome.Failed("That was taking too long, so I stopped.")
    }

    /** What the user is told is happening, in their words rather than the tool's. */
    private fun working(verb: String): String = when (verb) {
        "search" -> "Looking it up"
        "place", "nearby" -> "Finding places"
        "route" -> "Checking the route"
        "weather" -> "Checking the weather"
        "navigate" -> "Starting navigation"
        else -> "Working"
    }

    /** Never shows the user a stack trace or a coroutine's internal name. */
    private fun plain(error: Throwable): String {
        if (error is CancellationException) throw error
        val message = error.message.orEmpty()
        return when {
            error is AiError && message.isNotBlank() -> message
            message.contains("Unable to resolve host") || error is UnknownHostException ->
                "No connection."
            error is SocketTimeoutException -> "That took too long to answer."
            error is IOException -> "Could not reach your assistant."
            else -> "That did not go through."
        }
    }

    /** Byte-identical between steps and between runs, so it can sit in a cached prefix. */
    private fun system(): String = """
        You carry out requests for the user of an Android phone. Each turn you reply with
        exactly one JSON object and nothing else. No explanation, no markdown, no fences.

        These happen quietly in the background. Prefer them:
        {"do":"search","q":"..."}                    look something up
        {"do":"place","q":"..."}                     find where somewhere is
        {"do":"nearby","what":"cafe","near":"..."}   find things near a place
        {"do":"route","from":"...","to":"...","mode":"driving|walking|cycling"}
        {"do":"weather","place":"..."}               current weather and today's range
        {"do":"navigate","to":"..."}                 start turn by turn navigation

        Finishing:
        {"do":"ask","text":"..."}                    ask the user one short question
        {"do":"say","text":"..."}                    finish, with the answer for the user

        ${handsPrompt()}

        Answer from the background tools whenever they can do it. Only use the phone's apps
        when the request genuinely needs one, for example sending a message or reading
        something that exists only inside an app: opening an app puts it in front of the
        user and interrupts them.

        You may use several tools in a row before answering. When you finish, "say" should
        read like a person answering, not a report of what you did. Do not mention screens,
        steps, tools or lookups.

        Apps on this phone: ${apps().sorted().joinToString(", ")}
    """.trimIndent()

    private fun handsPrompt(): String = if (AgentService.running) {
        """
        These use the phone itself and are visible to the user, so keep them for last:
        {"do":"open","app":"Maps"}           launch an app by name
        {"do":"tap","ref":3}                 press control [3] on the screen shown
        {"do":"type","ref":5,"text":"..."}   type into field [5]
        {"do":"enter"}                       press the keyboard's go or search key
        {"do":"scroll","direction":"down"}   scroll the screen
        {"do":"back"}                        go back

        Only refer to control numbers from the screen you were just shown; numbers from
        earlier screens now mean different things. After typing into a search field you
        usually need "enter".
        """.trimIndent()
    } else {
        "Using the phone's own apps is switched off, so the tools above are all you have."
    }

    private fun parse(reply: String): Step? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
            ?: return null
        val verb = json.optString("do").takeIf { it.isNotBlank() } ?: return null
        return Step(verb, json)
    }

    private companion object {
        const val MAX_STEPS = 14
        const val MAX_MALFORMED = 2
        const val OPENING = 1400L
        const val SHORT = 500L
    }
}

/**
 * Which presses the user is asked about first.
 *
 * This is a word list, and a word list is not a safety guarantee. It cannot read an
 * unlabelled icon, it does not speak the user's language unless that language is English,
 * and "OK" on a screen asking whether to delete everything looks exactly like "OK"
 * anywhere else. So it errs the other way: anything unlabelled is treated as worth asking
 * about, because an unlabelled button is the case it can say least about. The wording
 * shown to the user says "usually", not "always", for the same reason.
 */
object Risk {
    private val WORDS = listOf(
        "send", "pay", "buy", "order", "purchase", "checkout", "place order",
        "delete", "remove", "uninstall", "erase", "clear", "discard",
        "post", "publish", "share", "tweet", "reply", "submit",
        "call", "dial", "transfer", "book", "reserve", "subscribe",
        "confirm", "agree", "accept", "allow", "continue", "done", "ok", "yes",
    )

    fun consequential(label: String): Boolean {
        val text = label.lowercase().trim().trim('.', '!', '\u2026')
        // An unlabelled control is the one this can say least about, so it asks.
        if (text.isEmpty()) return true
        val words = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }
        return WORDS.any { word -> text == word || words.contains(word) }
    }

    fun describe(label: String): String =
        if (label.isBlank()) "Press this button?" else "Press \"$label\"?"
}

/** Thrown when the agent cannot start at all. */
fun agentUnavailable(): AiError = AiError("Lumenberg is not allowed to operate the phone yet.")
