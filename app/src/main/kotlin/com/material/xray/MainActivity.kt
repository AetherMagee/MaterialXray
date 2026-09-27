package com.material.xray

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.core.locale.notifyAppLocaleChanged
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ThemePreset
import com.material.xray.ui.home.HomeDataState
import com.material.xray.ui.navigation.MainNavigation
import com.material.xray.ui.settings.SettingsDataState
import com.material.xray.ui.theme.MaterialXrayTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var homeDataState: HomeDataState

    @Inject lateinit var settingsDataState: SettingsDataState

    @Inject lateinit var settingsRepository: SettingsRepository

    private var pendingSubscriptionLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingSubscriptionLink = subscriptionLinkFromDeepLink(intent.dataString)
        notifyAppLocaleChanged()
        val navigationBarStyle = if (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(navigationBarStyle = navigationBarStyle)
        // Hold the splash screen until the home data snapshot is ready, so the first visible
        // frame renders the subscription list, server rows, and selected server together instead
        // of popping in piece by piece. On warm starts the snapshot is already loaded and the
        // splash screen dismisses on the first frame.
        val splashShownAtMillis = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            keepSplashOnScreen(
                initialDataLoaded = homeDataState.data.value != null &&
                    settingsDataState.data.value != null,
                elapsedMillis = SystemClock.uptimeMillis() - splashShownAtMillis,
            )
        }
        setContent {
            val settings by settingsDataState.data.collectAsStateWithLifecycle()
            MaterialXrayTheme(
                preset = settings?.themePreset ?: ThemePreset.Dynamic,
                oledDark = settings?.oledDark ?: false,
            ) {
                var diagnosticsNoticeVisible by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(settings?.diagnosticsNoticeShown) {
                    if (settings?.diagnosticsNoticeShown == false) {
                        diagnosticsNoticeVisible = true
                    }
                }
                Box {
                    MainNavigation(
                        pendingSubscriptionLink = pendingSubscriptionLink,
                        onSubscriptionLinkHandled = { pendingSubscriptionLink = null },
                        showDiagnosticsNotice = diagnosticsNoticeVisible,
                        onDiagnosticsNoticeDismiss = {
                            diagnosticsNoticeVisible = false
                            scope.launch { settingsRepository.markDiagnosticsNoticeShown() }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSubscriptionLink = subscriptionLinkFromDeepLink(intent.dataString)
    }
}

internal fun subscriptionLinkFromDeepLink(deepLink: String?): String? {
    val link = deepLink?.takeIf { it.startsWith(SUBSCRIPTION_DEEP_LINK_PREFIX) }
        ?.removePrefix(SUBSCRIPTION_DEEP_LINK_PREFIX)
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    return link?.takeIf { it.length > "https://".length }
}

/**
 * The splash screen stays up only while the home data snapshot is still loading, and never longer
 * than [SPLASH_SCREEN_TIMEOUT_MS], so a slow or failed load cannot hold it up indefinitely.
 */
internal fun keepSplashOnScreen(initialDataLoaded: Boolean, elapsedMillis: Long): Boolean = !initialDataLoaded && elapsedMillis < SPLASH_SCREEN_TIMEOUT_MS

internal const val SPLASH_SCREEN_TIMEOUT_MS = 2_000L

private const val SUBSCRIPTION_DEEP_LINK_PREFIX = "mxray://add/"
