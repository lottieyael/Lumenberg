package dev.lumenberg.ai

/**
 * A place the assistant can talk to. Users pick one by name; Lumenberg owns the URLs
 * so nobody has to know what a base path is.
 */
enum class Provider(
    val id: String,
    val label: String,
    val tagline: String,
    val base: String,
    val signIn: SignIn,
    /** Models we prefer when the account exposes many; first hit wins. */
    val prefer: List<String> = emptyList(),
) {
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        tagline = "Sign in once. Every major model, one account.",
        base = "https://openrouter.ai/api/v1",
        signIn = SignIn.OAUTH,
        prefer = listOf("openai/gpt", "anthropic/claude", "google/gemini"),
    ),
    COPILOT(
        id = "copilot",
        label = "GitHub Copilot",
        tagline = "Sign in with GitHub. Uses the Copilot seat you already pay for.",
        base = "https://api.githubcopilot.com",
        signIn = SignIn.DEVICE,
        prefer = listOf("gpt", "claude"),
    ),
    OPENAI(
        id = "openai",
        label = "OpenAI",
        tagline = "Your own OpenAI platform account.",
        base = "https://api.openai.com/v1",
        signIn = SignIn.KEY,
        prefer = listOf("gpt"),
    ),
    ANTHROPIC(
        id = "anthropic",
        label = "Anthropic",
        tagline = "Claude, direct from the Anthropic API.",
        base = "https://api.anthropic.com/v1",
        signIn = SignIn.KEY,
        prefer = listOf("claude"),
    ),
    GOOGLE(
        id = "google",
        label = "Google Gemini",
        tagline = "Gemini via Google AI Studio.",
        base = "https://generativelanguage.googleapis.com/v1beta/openai",
        signIn = SignIn.KEY,
        prefer = listOf("gemini"),
    ),
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        tagline = "DeepSeek's own API. Cheap, and strong at reasoning.",
        base = "https://api.deepseek.com/v1",
        signIn = SignIn.KEY,
        prefer = listOf("deepseek"),
    ),
    OLLAMA(
        id = "ollama",
        label = "On your network",
        tagline = "An Ollama or LM Studio machine you run yourself.",
        base = "",
        signIn = SignIn.HOST,
        prefer = listOf("llama", "qwen", "mistral"),
    ),
    ;

    /** Anthropic speaks its own wire format; everyone else is OpenAI-shaped. */
    val wire: Wire get() = if (this == ANTHROPIC) Wire.ANTHROPIC else Wire.OPENAI

    /**
     * Copilot's private chat endpoint has not been verified with tool calls yet. Local
     * servers vary by server and model, so they stay text-only until capability discovery exists.
     */
    val supportsTools: Boolean
        get() = this != COPILOT && this != OLLAMA

    /** Where a user goes to get a key, when a key is the only option. */
    val keyUrl: String?
        get() = when (this) {
            OPENAI -> "https://platform.openai.com/api-keys"
            ANTHROPIC -> "https://console.anthropic.com/settings/keys"
            GOOGLE -> "https://aistudio.google.com/apikey"
            DEEPSEEK -> "https://platform.deepseek.com/api_keys"
            else -> null
        }

    companion object {
        fun of(id: String?): Provider = entries.firstOrNull { it.id == id } ?: OPENROUTER
    }
}

enum class SignIn {
    /** Browser round trip, no key ever typed or shown. */
    OAUTH,

    /** Show a short code, the user types it into a browser on any device. */
    DEVICE,

    /** Paste a key from the provider's console. */
    KEY,

    /** Type a machine address; no credential at all. */
    HOST,
}

enum class Wire { OPENAI, ANTHROPIC }
