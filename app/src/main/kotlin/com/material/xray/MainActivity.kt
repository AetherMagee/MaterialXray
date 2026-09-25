package com.material.xray

import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.material.xray.core.locale.notifyAppLocaleChanged
import com.material.xray.data.db.DatabaseOpenChecker
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.RecoveryResetManager
import com.material.xray.ui.home.HomeDataState
import com.material.xray.ui.navigation.MainNavigation
import com.material.xray.ui.recovery.DatabaseRecoveryScreen
import com.material.xray.ui.settings.SettingsDataState
import com.material.xray.ui.theme.MaterialXrayTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var databaseOpenChecker: DatabaseOpenChecker

    @Inject lateinit var homeDataStateProvider: Provider<HomeDataState>

    @Inject lateinit var settingsDataStateProvider: Provider<SettingsDataState>

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var recoveryResetManager: RecoveryResetManager

    private var homeDataState: HomeDataState? = null
    private var settingsDataState: SettingsDataState? = null
    private var databaseReadiness by mutableStateOf(DatabaseReadiness.Checking)
    private var resetFailed by mutableStateOf(false)
    private var resetting by mutableStateOf(false)
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
                initialDataLoaded = databaseReadiness == DatabaseReadiness.Failed ||
                    (homeDataState?.data?.value != null && settingsDataState?.data?.value != null),
                elapsedMillis = SystemClock.uptimeMillis() - splashShownAtMillis,
            )
        }
        openDatabase()
        setContent {
            MaterialXrayTheme {
                when (databaseReadiness) {
                    DatabaseReadiness.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    DatabaseReadiness.Failed -> DatabaseRecoveryScreen(
                        resetFailed = resetFailed,
                        resetting = resetting,
                        onRetry = ::retryDatabaseOpen,
                        onReset = ::clearAppData,
                    )
                    DatabaseReadiness.Ready -> {
                        val loadedSettingsDataState = requireNotNull(settingsDataState)
                        MainContent(loadedSettingsDataState)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSubscriptionLink = subscriptionLinkFromDeepLink(intent.dataString)
    }

    private fun retryDatabaseOpen() {
        if (databaseReadiness != DatabaseReadiness.Failed || resetting) return
        openDatabase()
    }

    private fun openDatabase() {
        databaseReadiness = DatabaseReadiness.Checking
        lifecycleScope.launch {
            if (databaseOpenChecker.canRead()) {
                homeDataState = homeDataStateProvider.get()
                settingsDataState = settingsDataStateProvider.get()
                databaseReadiness = DatabaseReadiness.Ready
            } else {
                databaseReadiness = DatabaseReadiness.Failed
            }
        }
    }

    private fun clearAppData() {
        if (resetting) return
        resetting = true
        resetFailed = false
        lifecycleScope.launch {
            try {
                resetFailed = !recoveryResetManager.prepareForReset() ||
                    !getSystemService(ActivityManager::class.java).clearApplicationUserData()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                resetFailed = true
            } finally {
                resetting = false
            }
        }
    }

    @Composable
    private fun MainContent(settingsDataState: SettingsDataState) {
        val settings by settingsDataState.data.collectAsStateWithLifecycle()
        var diagnosticsNoticeVisible by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(settings?.diagnosticsNoticeShown) {
            if (settings?.diagnosticsNoticeShown == false) {
                diagnosticsNoticeVisible = true
                delay(DIAGNOSTICS_NOTICE_DURATION_MS)
                diagnosticsNoticeVisible = false
                settingsRepository.markDiagnosticsNoticeShown()
            }
        }
        Box {
            MainNavigation(
                pendingSubscriptionLink = pendingSubscriptionLink,
                onSubscriptionLinkHandled = { pendingSubscriptionLink = null },
            )
            DiagnosticsNotice(
                visible = diagnosticsNoticeVisible,
                onDismiss = {
                    diagnosticsNoticeVisible = false
                    scope.launch { settingsRepository.markDiagnosticsNoticeShown() }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 76.dp),
            )
        }
    }

    private enum class DatabaseReadiness { Checking, Ready, Failed }
}

@Composable
private fun DiagnosticsNotice(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        ElevatedCard(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.diagnostics_default_notice),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.diagnostics_dismiss),
                    )
                }
            }
        }
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
private const val DIAGNOSTICS_NOTICE_DURATION_MS = 15_000L
private const val SUBSCRIPTION_DEEP_LINK_PREFIX = "mxray://add/"
