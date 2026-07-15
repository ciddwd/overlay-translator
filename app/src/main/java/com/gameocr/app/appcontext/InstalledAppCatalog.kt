package com.gameocr.app.appcontext

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SELECTABLE_APP_ICON_SIZE_DP = 40

data class SelectableApp(
    val packageName: String,
    val displayName: String,
    val icon: Bitmap? = null,
)

internal fun selectableAppIconSizePx(density: Float): Int =
    (SELECTABLE_APP_ICON_SIZE_DP * density).roundToInt().coerceAtLeast(1)

internal object SelectableAppPolicy {
    fun normalize(apps: List<SelectableApp>, ownPackageName: String): List<SelectableApp> {
        return apps.asSequence()
            .map { app ->
                val packageName = app.packageName.trim()
                val displayName = app.displayName.trim().ifBlank { packageName }
                app.copy(packageName = packageName, displayName = displayName)
            }
            .filter { it.packageName.isNotEmpty() && it.packageName != ownPackageName }
            .sortedWith(
                compareBy<SelectableApp>(
                    { it.displayName.lowercase(Locale.ROOT) },
                    { it.packageName.lowercase(Locale.ROOT) },
                )
            )
            .distinctBy(SelectableApp::packageName)
            .toList()
    }

    fun filter(apps: List<SelectableApp>, query: String): List<SelectableApp> {
        val tokens = query.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        if (tokens.isEmpty()) return apps

        return apps.filter { app ->
            val searchable = "${app.displayName}\n${app.packageName}".lowercase(Locale.ROOT)
            tokens.all(searchable::contains)
        }
    }
}

@Singleton
class InstalledAppCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun launchableApps(): List<SelectableApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val iconSizePx = selectableAppIconSizePx(context.resources.displayMetrics.density)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }
        SelectableAppPolicy.normalize(
            apps = resolved.mapNotNull { info ->
                val packageName = info.activityInfo?.packageName.orEmpty()
                if (packageName.isBlank()) {
                    null
                } else {
                    SelectableApp(
                        packageName = packageName,
                        displayName = runCatching { info.loadLabel(packageManager).toString() }
                            .getOrDefault(packageName),
                        icon = runCatching {
                            info.loadIcon(packageManager).toBitmap(
                                width = iconSizePx,
                                height = iconSizePx,
                                config = Bitmap.Config.ARGB_8888,
                            )
                        }.getOrNull(),
                    )
                }
            },
            ownPackageName = context.packageName,
        )
    }
}
