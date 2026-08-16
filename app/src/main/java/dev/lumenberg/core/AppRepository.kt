package dev.lumenberg.core

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process

class AppRepository(private val context: Context) {
    private val launcherApps = requireNotNull(context.getSystemService(LauncherApps::class.java))
    private val prefs = context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)

    fun loadApps(): List<LauncherApp> {
        val user = Process.myUserHandle()
        return launcherApps.getActivityList(null, user)
            .asSequence()
            .filter { it.componentName.packageName != context.packageName }
            .map { info ->
                LauncherApp(
                    label = info.label.toString(),
                    component = info.componentName,
                    user = user,
                    launchScore = prefs.getLong(info.componentName.flattenToString(), 0L),
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun ranked(query: String, apps: List<LauncherApp>): List<LauncherApp> =
        apps.mapNotNull { app ->
            val score = SearchRanker.score(
                query,
                SearchRanker.Candidate(app.label, app.launchScore),
            )
            if (score == Long.MIN_VALUE) null else app to score
        }.sortedWith(
            compareByDescending<Pair<LauncherApp, Long>> { it.second }
                .thenBy { it.first.label.lowercase() },
        ).map { it.first }

    fun launch(app: LauncherApp) {
        launcherApps.startMainActivity(app.component, app.user, null, null)
        val key = app.component.flattenToString()
        val next = prefs.getLong(key, 0L) + 1L
        prefs.edit().putLong(key, next).apply()
    }
}
