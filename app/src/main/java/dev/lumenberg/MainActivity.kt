package dev.lumenberg

import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lumenberg.ai.AiSettings
import dev.lumenberg.ai.AiSettingsStore
import dev.lumenberg.ui.HomeScreen
import dev.lumenberg.ui.LumenbergTheme
import dev.lumenberg.widgets.WidgetStore

class MainActivity : ComponentActivity() {
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var widgetStore: WidgetStore
    private lateinit var aiStore: AiSettingsStore

    private var widgetIds by mutableStateOf(emptyList<Int>())
    private var aiSettings by mutableStateOf(AiSettings("", "", ""))
    private var homeRoleHeld by mutableStateOf(false)
    private var onboardingDone by mutableStateOf(false)
    private var voiceText by mutableStateOf<String?>(null)
    private var pendingWidgetId: Int? = null

    private val widgetPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            ?.takeIf { it >= 0 }
            ?: pendingWidgetId
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            configureWidgetIfNeeded(id)
        } else if (id != null) {
            appWidgetHost.deleteAppWidgetId(id)
        }
        pendingWidgetId = null
    }

    private val widgetConfigure = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingWidgetId
        if (id != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                persistWidget(id)
            } else {
                appWidgetHost.deleteAppWidgetId(id)
            }
        }
        pendingWidgetId = null
    }

    private val voiceRecognizer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        voiceText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        widgetStore = WidgetStore(this)
        aiStore = AiSettingsStore(this)
        widgetIds = widgetStore.load()
        aiSettings = aiStore.load()
        onboardingDone = getSharedPreferences("onboarding", MODE_PRIVATE)
            .getBoolean("done", false)
        refreshHomeRole()

        setContent {
            LumenbergTheme {
                HomeScreen(
                    appWidgetHost = appWidgetHost,
                    widgetIds = widgetIds,
                    aiSettings = aiSettings,
                    homeRoleHeld = homeRoleHeld,
                    onboardingDone = onboardingDone,
                    voiceText = voiceText,
                    onVoiceConsumed = { voiceText = null },
                    onRequestHomeRole = ::requestHomeRole,
                    onAddWidget = ::pickWidget,
                    onRemoveWidget = ::removeWidget,
                    onStartVoice = ::startVoiceRecognition,
                    onSaveAiSettings = ::saveAiSettings,
                    onFinishOnboarding = ::finishOnboarding,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        widgetIds = widgetStore.load()
    }

    override fun onStop() {
        appWidgetHost.stopListening()
        super.onStop()
    }

    private fun refreshHomeRole() {
        homeRoleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            manager?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName == packageName
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java) ?: return
            if (manager.isRoleAvailable(RoleManager.ROLE_HOME) && !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivity(manager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            }
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    private fun pickWidget() {
        val id = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        runCatching { widgetPicker.launch(intent) }
            .onFailure {
                appWidgetHost.deleteAppWidgetId(id)
                pendingWidgetId = null
            }
    }

    private fun configureWidgetIfNeeded(id: Int) {
        val manager = AppWidgetManager.getInstance(this)
        val info = manager.getAppWidgetInfo(id)
        val configure = info?.configure
        if (configure == null) {
            persistWidget(id)
            pendingWidgetId = null
            return
        }

        pendingWidgetId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = configure
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        runCatching { widgetConfigure.launch(intent) }
            .onFailure {
                appWidgetHost.deleteAppWidgetId(id)
                pendingWidgetId = null
            }
    }

    private fun persistWidget(id: Int) {
        widgetStore.add(id)
        widgetIds = widgetStore.load()
    }

    private fun removeWidget(id: Int) {
        widgetStore.remove(id)
        appWidgetHost.deleteAppWidgetId(id)
        widgetIds = widgetStore.load()
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask or launch")
        }
        runCatching { voiceRecognizer.launch(intent) }
    }

    private fun saveAiSettings(settings: AiSettings) {
        aiStore.save(settings)
        aiSettings = settings
    }

    private fun finishOnboarding() {
        getSharedPreferences("onboarding", MODE_PRIVATE)
            .edit()
            .putBoolean("done", true)
            .apply()
        onboardingDone = true
    }

    companion object {
        private const val APP_WIDGET_HOST_ID = 0x0A11
    }
}
