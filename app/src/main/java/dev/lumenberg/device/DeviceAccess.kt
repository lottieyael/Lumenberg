package dev.lumenberg.device

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class DeviceCapability {
    NOTIFICATIONS,
    CALENDAR,
    CONTACTS,
    USAGE,
    LOCATION,
}

data class DeviceAccessState(
    val notifications: Boolean = false,
    val calendar: Boolean = false,
    val contacts: Boolean = false,
    val usage: Boolean = false,
    val location: Boolean = false,
) {
    fun has(capability: DeviceCapability): Boolean = when (capability) {
        DeviceCapability.NOTIFICATIONS -> notifications
        DeviceCapability.CALENDAR -> calendar
        DeviceCapability.CONTACTS -> contacts
        DeviceCapability.USAGE -> usage
        DeviceCapability.LOCATION -> location
    }
}

class DeviceAccess(private val context: Context) {
    fun snapshot(): DeviceAccessState = DeviceAccessState(
        notifications = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        calendar = granted(Manifest.permission.READ_CALENDAR),
        contacts = granted(Manifest.permission.READ_CONTACTS),
        usage = usageGranted(),
        location = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun usageGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }
}
