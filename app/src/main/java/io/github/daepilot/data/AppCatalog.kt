package io.github.daepilot.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

/**
 * Apps that expose a launcher entry. Package visibility on Android 11+ limits
 * the list to packages matched by the manifest `<queries>` declaration; the
 * picker also accepts manually entered package names.
 */
object AppCatalog {
    suspend fun launchableApps(context: Context): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching { pm.queryIntentActivities(intent, 0) }
                .getOrDefault(emptyList())
                .asSequence()
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .map { packageName ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                    }.getOrDefault(packageName)
                    InstalledApp(packageName, label)
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
        }

    fun isValidPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+(:[A-Za-z0-9_.]+)?"))
}
