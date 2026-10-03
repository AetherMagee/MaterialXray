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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.material.xray.core.locale.notifyAppLocaleChanged
import com.material.xray.core.ui.R
import com.material.xray.data.db.DatabaseOpenChecker
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.RecoveryResetManager
import com.material.xray.ui.adaptive.useNavigationRail
import com.material.xray.ui.components.TooltipIconButton
import com.material.xray.ui.home.HomeDataState
import com.material.xray.ui.navigation.MainNavigation
import com.material.xray.ui.recovery.DatabaseRecoveryScreen
import com.material.xray.ui.settings.SettingsDataState
import com.material.xray.ui.theme.MaterialXrayTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private val databaseOpenChecker: DatabaseOpenChecker by inject()

    private val settingsRepository: SettingsRepository by inject()

    private val recoveryResetManager: RecoveryResetManager by inject()

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
        // splash screen dismisses on the first frame. A slow or failed load cannot hold it up
        // longer than the timeout.
        val splashShownAtMillis = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val initialDataLoaded = databaseReadiness == DatabaseReadiness.Failed ||
                (homeDataState?.data?.value != null && settingsDataState?.data?.value != null)
            !initialDataLoaded && SystemClock.uptimeMillis() - splashShownAtMillis < SPLASH_SCREEN_TIMEOUT_MS
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
                // Resolved only once the database is readable, because both start loading from it.
                homeDataState = get<HomeDataState>()
                settingsDataState = get<SettingsDataState>()
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
        val addSubscriptionFocusRequester = remember { FocusRequester() }
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
                addSubscriptionFocusRequester = addSubscriptionFocusRequester,
            )
            DiagnosticsNotice(
                visible = diagnosticsNoticeVisible,
                focusAfterHiding = addSubscriptionFocusRequester,
                onDismiss = {
                    diagnosticsNoticeVisible = false
                    scope.launch { settingsRepository.markDiagnosticsNoticeShown() }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Clears the bottom navigation bar; the rail layout has none to clear.
                    .padding(start = 16.dp, end = 16.dp, bottom = if (useNavigationRail()) 16.dp else 76.dp),
            )
        }
    }

    private enum class DatabaseReadiness { Checking, Ready, Failed }
}

@Composable
private fun DiagnosticsNotice(
    visible: Boolean,
    focusAfterHiding: FocusRequester,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A remote can only reach the notice if it starts there. Touch mode refuses the request, so
    // phones keep focus where it was.
    val dismissFocusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            dismissFocusRequester.requestFocus()
        } else if (focused) {
            // On first launch there is nothing to connect yet, so adding a subscription comes next.
            focusAfterHiding.requestFocus()
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        ElevatedCard(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .onFocusChanged { focused = it.hasFocus },
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
                TooltipIconButton(
                    tooltip = stringResource(R.string.diagnostics_dismiss),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(dismissFocusRequester),
                ) {
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

private const val SPLASH_SCREEN_TIMEOUT_MS = 2_000L
private const val DIAGNOSTICS_NOTICE_DURATION_MS = 15_000L
private const val SUBSCRIPTION_DEEP_LINK_PREFIX = "mxray://add/"
