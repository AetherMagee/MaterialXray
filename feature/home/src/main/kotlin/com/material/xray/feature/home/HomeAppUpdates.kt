package com.material.xray.feature.home

import com.material.xray.core.data.repository.AppUpdateRepository
import com.material.xray.core.model.AppUpdate
import com.material.xray.core.runtime.AppUpdateChecker
import com.material.xray.core.runtime.AppUpdateInstallProgress
import com.material.xray.core.runtime.AppUpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Singleton

/** What the Home screen needs from app updates: the offer, the install and its permission prompt. */
@Singleton
class HomeAppUpdates(
    repository: AppUpdateRepository,
    private val checker: AppUpdateChecker,
    private val installer: AppUpdateInstaller,
) {
    val availableUpdate: Flow<AppUpdate?> = repository.availableUpdate
    val installProgress: StateFlow<AppUpdateInstallProgress?> = installer.installProgress
    val installPermissionRationaleRequired: StateFlow<Boolean> = installer.installPermissionRationaleRequired

    /** Update checks are best-effort and should not interrupt the Home screen. */
    suspend fun checkIfDue() {
        succeeded { checker.check() }
    }

    // The install steps report failure instead of throwing, so the screen can tell the user.
    suspend fun install(update: AppUpdate): Boolean = succeeded { installer.install(update) }

    suspend fun resumePendingInstall(): Boolean = succeeded { installer.resumePendingInstall() }

    suspend fun confirmInstallPermissionRationale(): Boolean = succeeded {
        installer.confirmInstallPermissionRationale()
    }

    fun dismissInstallPermissionRationale() {
        installer.dismissInstallPermissionRationale()
    }

    private suspend fun succeeded(step: suspend () -> Unit): Boolean = try {
        step()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
