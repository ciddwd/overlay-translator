package com.gameocr.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gameocr.app.data.AppLocalePrefs
import com.gameocr.app.data.ThemeModePrefs
import com.gameocr.app.onboarding.OnboardingPrefs
import com.gameocr.app.onboarding.OnboardingScreen
import com.gameocr.app.onboarding.FloatingTourRerunPolicy
import com.gameocr.app.overlay.FloatingMenuTourPrefs
import com.gameocr.app.service.CaptureService
import com.gameocr.app.service.CaptureServiceState
import com.gameocr.app.ui.theme.GameOcrTheme
import com.gameocr.app.ui.theme.LocalThemeMode
import com.gameocr.app.ui.theme.ThemeModeController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val routeRequest = mutableStateOf<String?>(null)

    companion object {
        const val EXTRA_START_ROUTE: String = "com.gameocr.app.extra.START_ROUTE"
        const val ROUTE_SETTINGS: String = "Settings"
    }

    /**
     * 在 Activity 的 baseContext 被设置之前，用持久化的 locale 包装它。
     * 重写在 [onCreate] 之前就被调用，确保整个 Activity 生命周期内 Resources 用对的 locale。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocalePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeRequest.value = intent?.getStringExtra(EXTRA_START_ROUTE)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // 主题模式：从 prefs 初始化；切换后通过 CompositionLocal 透传到 GameOcrTheme，
            // 无需重建 Activity 即可瞬时生效。
            var themeMode by remember { mutableIntStateOf(ThemeModePrefs.read(context)) }
            val controller = ThemeModeController(
                mode = themeMode,
                setMode = { newMode ->
                    themeMode = newMode
                    ThemeModePrefs.write(context, newMode)
                }
            )
            CompositionLocalProvider(LocalThemeMode provides controller) {
                GameOcrTheme(themeMode = themeMode) {
                    AppRoot(routeRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeRequest.value = intent.getStringExtra(EXTRA_START_ROUTE)
    }
}

private enum class Route { Main, Onboarding, Settings, Glossary, Logs, LegalNotices }

@Composable
private fun AppRoot(routeRequest: State<String?>) {
    val context = LocalContext.current
    var onboardingFirstRun by rememberSaveable {
        mutableStateOf(!OnboardingPrefs.isCompleted(context))
    }
    var onboardingOpenedFromHelp by rememberSaveable {
        mutableStateOf(false)
    }
    // 用 rememberSaveable：语言切换会触发系统 recreate Activity，route 须跨重建保留。
    var routeName by rememberSaveable {
        mutableStateOf(
            routeRequest.value ?: if (onboardingFirstRun) {
                Route.Onboarding.name
            } else {
                Route.Main.name
            }
        )
    }
    val settingsListState = rememberLazyListState()
    LaunchedEffect(routeRequest.value) {
        val requested = routeRequest.value ?: return@LaunchedEffect
        if (Route.entries.any { it.name == requested }) {
            routeName = requested
        }
    }
    val route = Route.valueOf(routeName)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (route) {
            Route.Main -> MainScreen(
                onOpenSettings = { routeName = Route.Settings.name },
                onOpenLogs = { routeName = Route.Logs.name },
                onOpenLegalNotices = { routeName = Route.LegalNotices.name },
                onOpenOnboarding = {
                    val decision = FloatingTourRerunPolicy.onHelpOpened()
                    if (decision.resetCompletion) {
                        FloatingMenuTourPrefs.reset(context)
                    }
                    onboardingOpenedFromHelp = true
                    onboardingFirstRun = false
                    routeName = Route.Onboarding.name
                },
            )
            Route.Onboarding -> OnboardingScreen(
                firstRun = onboardingFirstRun,
                onFinished = {
                    val decision = FloatingTourRerunPolicy.onOnboardingExit(
                        openedFromHelp = onboardingOpenedFromHelp,
                        completed = true,
                        captureServiceRunning = CaptureServiceState.running.value,
                    )
                    OnboardingPrefs.markCompleted(context)
                    onboardingFirstRun = false
                    onboardingOpenedFromHelp = false
                    routeName = Route.Main.name
                    if (decision.notifyRunningService) {
                        context.startService(
                            CaptureService.runFloatingTourIntent(context)
                        )
                    }
                },
                onSkipped = {
                    OnboardingPrefs.markCompleted(context)
                    onboardingFirstRun = false
                    onboardingOpenedFromHelp = false
                    routeName = Route.Main.name
                },
            )
            Route.Settings -> SettingsScreen(
                onBack = { routeName = Route.Main.name },
                onOpenGlossary = { routeName = Route.Glossary.name },
                listState = settingsListState,
            )
            Route.Glossary -> GlossaryScreen(onBack = { routeName = Route.Settings.name })
            Route.Logs -> LogScreen(onBack = { routeName = Route.Main.name })
            Route.LegalNotices -> LegalNoticesScreen(onBack = { routeName = Route.Main.name })
        }
    }
}
