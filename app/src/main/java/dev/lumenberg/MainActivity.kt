package dev.lumenberg

import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.lumenberg.ai.Account
import dev.lumenberg.ai.OpenRouterAuth
import dev.lumenberg.ai.Provider
import dev.lumenberg.core.AppRepository
import dev.lumenberg.ui.Connect
import dev.lumenberg.ui.Home
import dev.lumenberg.ui.LumenbergTheme
import dev.lumenberg.ui.Motion
import dev.lumenberg.ui.Onboarding
import dev.lumenberg.ui.Picker
import dev.lumenberg.ui.Session
import dev.lumenberg.ui.Settings as SettingsPane
import dev.lumenberg.ui.Sheet
import dev.lumenberg.widgets.Offer
import dev.lumenberg.widgets.Panel
import dev.lumenberg.widgets.WidgetStore
import kotlinx.coroutines.launch

private enum class Overlay { None, Onboarding, Connect, Settings, Widgets }

class MainActivity : ComponentActivity() {
    private lateinit var host: AppWidgetHost
    private lateinit var widgets: WidgetStore
    private lateinit var repository: AppRepository
    private lateinit var session: Session
    private lateinit var auth: OpenRouterAuth
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private var panels by mutableStateOf(emptyList<Panel>())
    private var homeRoleHeld by mutableStateOf(false)
    private var voice by mutableStateOf<String?>(null)
    private var overlay by mutableStateOf(Overlay.None)
    private var step by mutableIntStateOf(0)
    private var dynamicColour by mutableStateOf(true)
    private var signingIn by mutableStateOf(false)
    private var signInError by mutableStateOf<String?>(null)
    private var connectFrom = Overlay.Settings
    private var pendingWidget: Int? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.idExtra() ?: pendingWidget
        if (result.resultCode == Activity.RESULT_OK && id != null) configureOrKeep(id) else discard(id)
    }

    private val configureWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.idExtra() ?: pendingWidget
        if (result.resultCode == Activity.RESULT_OK && id != null) keep(id) else discard(id)
    }

    /**
     * The result intent carries the id, and reading it there is what survives the launcher
     * being killed while a widget's configuration screen is in front.
     */
    private fun androidx.activity.result.ActivityResult.idExtra(): Int? =
        data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }

    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        voice = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        host = AppWidgetHost(this, HOST_ID)
        widgets = WidgetStore(this)
        repository = AppRepository(this, lifecycleScope)
        session = Session(this, lifecycleScope)
        auth = OpenRouterAuth(this)

        dynamicColour = prefs.getBoolean("dynamic", true)
        overlay = if (prefs.getBoolean("onboarded", false)) Overlay.None else Overlay.Onboarding
        reloadPanels()
        refreshHomeRole()
        handleAuthRedirect(intent)

        setContent {
            LumenbergTheme(dynamic = dynamicColour) {
                val apps by repository.apps.collectAsState()

                Home(
                    repository = repository,
                    session = session,
                    host = host,
                    panels = panels,
                    apps = apps,
                    enabled = overlay == Overlay.None,
                    homeRoleHeld = homeRoleHeld,
                    voice = voice,
                    onVoiceUsed = { voice = null },
                    onStartVoice = ::startListening,
                    onSettings = { overlay = Overlay.Settings },
                    onConnect = ::openConnect,
                    onAddWidget = ::addWidget,
                    onRemoveWidget = ::removeWidget,
                    onResizeWidget = { id, height -> widgets.resize(id, height); reloadPanels() },
                    onMoveWidget = { id, by -> widgets.move(id, by); reloadPanels() },
                    onRequestHome = ::requestHomeRole,
                )

                var showing by remember { mutableStateOf(overlay) }
                LaunchedEffect(overlay) { if (overlay != Overlay.None) showing = overlay }

                AnimatedVisibility(
                    visible = overlay != Overlay.None,
                    enter = slideInVertically(Motion.Settle) { it / 4 } + fadeIn(Motion.Fade),
                    exit = slideOutVertically(Motion.Settle) { it / 4 } + fadeOut(Motion.Fade),
                ) {
                    BackHandler(enabled = showing != Overlay.Onboarding) {
                        overlay = if (showing == Overlay.Connect) connectFrom else Overlay.None
                    }
                    when (showing) {
                        Overlay.Settings -> Sheet("Settings", onClose = { overlay = Overlay.None }) {
                            SettingsPane(
                                session = session,
                                homeRoleHeld = homeRoleHeld,
                                dynamicColour = dynamicColour,
                                onDynamicColour = ::useDynamicColour,
                                onRequestHome = ::requestHomeRole,
                                onConnect = ::openConnect,
                                onAddWidget = ::addWidget,
                            )
                        }
                        Overlay.Widgets -> Sheet("Add a widget", onClose = { overlay = Overlay.None }) {
                            Picker(onChoose = ::chooseWidget)
                        }
                        Overlay.Connect -> Sheet("Connect an assistant", onClose = { overlay = connectFrom }) {
                            Connect(
                                session = session,
                                signingIn = signingIn,
                                signInError = signInError,
                                onOAuth = ::signInWithOpenRouter,
                                onOpenUrl = ::openUrl,
                                onDone = { overlay = Overlay.None },
                            )
                        }
                        else -> Sheet("Lumenberg", onClose = null) {
                            Onboarding(
                                step = step,
                                homeRoleHeld = homeRoleHeld,
                                aiReady = session.ready,
                                onRequestHome = ::requestHomeRole,
                                onConnect = ::openConnect,
                                onNext = { step++ },
                                onSkip = ::finishOnboarding,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && overlay != Overlay.Onboarding) {
            overlay = Overlay.None
        }
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
        repository.start()
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        reloadPanels()
    }

    override fun onStop() {
        host.stopListening()
        repository.stop()
        super.onStop()
    }

    private fun openConnect() {
        if (overlay != Overlay.Connect) connectFrom = overlay
        overlay = Overlay.Connect
    }

    private fun signInWithOpenRouter() {
        signInError = null
        runCatching { startActivity(auth.authorizeIntent()) }
            .onFailure { signInError = "No browser on this device could open the sign-in page." }
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val code = auth.codeIn(intent?.data) ?: return
        setIntent(Intent(this, MainActivity::class.java))
        overlay = Overlay.Connect
        signingIn = true
        lifecycleScope.launch {
            signInError = runCatching { auth.exchange(code) }
                .fold(
                    onSuccess = { key ->
                        session.connect(Account(provider = Provider.OPENROUTER, credential = key))
                    },
                    onFailure = { it.message ?: "That sign-in did not complete." },
                )
            signingIn = false
            if (session.ready) {
                finishOnboarding()
                overlay = Overlay.None
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun addWidget() {
        overlay = Overlay.Widgets
    }

    private fun chooseWidget(offer: Offer) {
        overlay = Overlay.None
        discard(pendingWidget)
        val id = host.allocateAppWidgetId()
        pendingWidget = id
        val manager = AppWidgetManager.getInstance(this)
        val bound = runCatching {
            manager.bindAppWidgetIdIfAllowed(id, offer.provider.profile, offer.provider.provider, null)
        }.getOrDefault(false)

        if (bound) {
            configureOrKeep(id)
            return
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, offer.provider.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, offer.provider.profile)
        runCatching { bindWidget.launch(intent) }.onFailure { discard(id) }
    }

    private fun configureOrKeep(id: Int) {
        val configure = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.configure
        if (configure == null) {
            keep(id)
            return
        }
        pendingWidget = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        runCatching { configureWidget.launch(intent) }.onFailure {
            keep(id)
        }
    }

    private fun keep(id: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        widgets.add(id, Panel.fitHeight(info?.minHeight ?: 0, resources.displayMetrics))
        pendingWidget = null
        panels = widgets.load()
    }

    private fun discard(id: Int?) {
        id?.let { host.deleteAppWidgetId(it) }
        pendingWidget = null
    }

    private fun removeWidget(id: Int) {
        widgets.remove(id)
        host.deleteAppWidgetId(id)
        reloadPanels()
    }

    private fun reloadPanels() {
        val live = runCatching { host.appWidgetIds.toSet() }.getOrDefault(emptySet())
        if (live.isNotEmpty()) widgets.retain(live)
        panels = widgets.load()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask or launch")
        runCatching { listen.launch(intent) }
            .onFailure { voice = null; session.report("This phone has no voice input installed.") }
    }

    private fun refreshHomeRole() {
        homeRoleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == packageName
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java) ?: return
            if (manager.isRoleAvailable(RoleManager.ROLE_HOME) && !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                runCatching { startActivity(manager.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun useDynamicColour(on: Boolean) {
        dynamicColour = on
        prefs.edit().putBoolean("dynamic", on).apply()
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean("onboarded", true).apply()
        overlay = Overlay.None
    }

    private companion object {
        const val HOST_ID = 0x0A11
    }
}
