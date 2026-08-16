package dev.lumenberg.core

import android.content.ComponentName
import android.os.UserHandle

/** A single activity the system exposes as launchable. */
data class LauncherApp(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val launchScore: Long = 0L,
)
