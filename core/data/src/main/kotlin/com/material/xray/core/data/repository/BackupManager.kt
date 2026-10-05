package com.material.xray.core.data.repository

import com.material.xray.core.common.connection.AppUpdateScheduling
import com.material.xray.core.common.connection.ConnectionShutdown
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.data.platform.BackupStorage
import com.material.xray.core.data.platform.LauncherIconSwitcher
import com.material.xray.core.database.AppDatabase
import com.material.xray.core.database.entity.AppRouteAssignment
import com.material.xray.core.database.entity.ServerEntity
import com.material.xray.core.database.entity.SubscriptionEntity
import com.material.xray.core.database.entity.routeAssignment
import com.material.xray.core.database.entity.toAppBypassEntity
import com.material.xray.core.database.withWriteTransaction
import com.material.xray.core.model.BackupData
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.model.appKey
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.core.xray.XrayPaths
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Singleton

@Singleton
class BackupManager(
    private val backupStorage: BackupStorage,
    private val xrayPaths: XrayPaths,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val launcherIconSwitcher: LauncherIconSwitcher,
    private val appUpdateScheduler: AppUpdateScheduling,
    private val connectionShutdown: ConnectionShutdown,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val activeConfigOverrideStore: ActiveConfigOverrideStore,
    private val sections: List<BackupSection>,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val subscriptionDao = database.subscriptionDao()
    private val serverDao = database.serverDao()
    private val appBypassDao = database.appBypassDao()
    private val operationMutex = Mutex()
    private val journalStore = BackupRestoreJournalStore(xrayPaths.filesDir, json)

    suspend fun export(locator: String): BackupSummary = operationMutex.withLock {
        recoverInterruptedRestoreLocked()
        val snapshot = createSnapshot()
        val output = backupStorage.openOutput(locator)
            ?: throw IOException("Unable to open the selected backup destination")
        output.use { stream ->
            stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            stream.flush()
        }
        BackupImportPlanner.create(snapshot).toSummary()
    }

    fun prepareImport(locator: String): PreparedBackupImport {
        val backup = decodeBackup(readBackup(locator))
        return PreparedBackupImport(createImportPlan(backup))
    }

    suspend fun restore(prepared: PreparedBackupImport) = operationMutex.withLock {
        recoverInterruptedRestoreLocked()
        disconnectActiveConnection()
        val previous = createSnapshot()
        journalStore.write(BackupRestoreJournal(previous))

        val result = runCatching {
            applyPlan(prepared.plan)
            applyExternalSettings()
            journalStore.delete()
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                rollbackAfterFailure(previous, error)
                throw error
            }
            rollbackAfterFailure(previous, error)
            throw IOException("Unable to restore the backup", error)
        }
    }

    suspend fun recoverInterruptedRestore(): Boolean = operationMutex.withLock {
        recoverInterruptedRestoreLocked()
    }

    private suspend fun recoverInterruptedRestoreLocked(): Boolean {
        val journal = journalStore.read() ?: return false
        applyPlan(BackupImportPlanner.create(journal.previous))
        applyExternalSettings()
        journalStore.delete()
        return true
    }

    private suspend fun createSnapshot(): BackupData {
        val settings = settingsRepository.getAllAsMap()
        val (subscriptions, servers, appRoutes) = database.withWriteTransaction {
            Triple(subscriptionDao.getAll(), serverDao.getAll(), appBypassDao.getAll())
        }
        val subscriptionKeyById = subscriptions.associate { subscription ->
            subscription.id to "subscription-${subscription.id}"
        }
        val serverKeyById = servers.associate { server -> server.id to "$SERVER_KEY_PREFIX${server.id}" }
        val selectedServerKey = settings[LAST_SERVER_ID_SETTING]
            ?.toLongOrNull()
            ?.let(serverKeyById::get)

        return BackupData(
            version = BackupData.CURRENT_VERSION,
            subscriptions = subscriptions.map { subscription ->
                BackupData.BackupSubscription(
                    key = subscriptionKeyById.getValue(subscription.id),
                    name = subscription.name,
                    url = subscription.url,
                    preferJson = subscription.preferJson,
                    autoUpdateIntervalHours = subscription.autoUpdateIntervalHours,
                    descriptionHidden = subscription.descriptionHidden,
                    userAgentMode = subscription.userAgentMode,
                    customUserAgent = subscription.customUserAgent,
                    customHeaders = subscription.customHeaders,
                    allowInsecureUpdates = subscription.allowInsecureUpdates,
                    lastUpdated = subscription.lastUpdated,
                    lastAutoRefreshFailureAt = subscription.lastAutoRefreshFailureAt,
                    metadata = subscription.toSubscriptionMetadata(),
                    appRouting = subscription.toSubscriptionAppRouting(),
                    routing = subscription.toSubscriptionRouting(),
                )
            },
            servers = servers.map { server ->
                BackupData.BackupServer(
                    key = serverKeyById.getValue(server.id),
                    subscriptionKey = subscriptionKeyById.getValue(server.subscriptionId),
                    subscriptionUrl = subscriptions.first { it.id == server.subscriptionId }.url,
                    config = json.decodeFromString<ServerConfig>(server.configJson),
                    edited = server.edited,
                    guarded = server.guarded,
                )
            },
            bypassedApps = appRoutes
                .filter { it.routeAssignment().mode == com.material.xray.core.database.entity.AppRouteMode.Bypass }
                .map { appKey(it.profileId, it.packageName) },
            settings = settings,
            appRoutes = appRoutes.map { route ->
                val assignment = route.routeAssignment()
                BackupData.BackupAppRoute(
                    packageName = route.packageName,
                    profileId = route.profileId,
                    mode = assignment.mode.name,
                    serverKey = assignment.serverId?.let(serverKeyById::get),
                    manual = route.manual,
                    alwaysProxied = assignment.alwaysProxied,
                )
            },
            selectedServerKey = selectedServerKey,
            activeConfigOverride = activeConfigOverrideStore.read(),
            sections = sections.associate { section -> section.key to section.export() },
        )
    }

    private suspend fun applyPlan(plan: BackupImportPlan) {
        var selectedServerId = -1L
        var serverIdByKey = emptyMap<String, Long>()
        database.withWriteTransaction {
            appBypassDao.deleteAll()
            subscriptionDao.deleteAll()

            val subscriptionIdByKey = plan.subscriptions.withIndex().associate { (sortOrder, planned) ->
                planned.key to subscriptionDao.insert(
                    SubscriptionEntity(
                        name = planned.value.name,
                        url = planned.value.url,
                        preferJson = planned.value.preferJson,
                        autoUpdateIntervalHours = planned.value.autoUpdateIntervalHours,
                        descriptionHidden = planned.value.descriptionHidden,
                        userAgentMode = planned.value.userAgentMode,
                        customUserAgent = planned.value.customUserAgent,
                        customHeaders = planned.value.customHeaders,
                        allowInsecureUpdates = planned.value.allowInsecureUpdates,
                        lastUpdated = planned.value.lastUpdated,
                        lastAutoRefreshFailureAt = planned.value.lastAutoRefreshFailureAt,
                        sortOrder = sortOrder,
                    ).withSubscriptionMetadata(planned.value.metadata)
                        .withSubscriptionAppRouting(planned.value.appRouting)
                        .withSubscriptionRouting(planned.value.routing),
                )
            }
            val serverEntities = plan.servers.map { planned ->
                ServerEntity(
                    subscriptionId = subscriptionIdByKey.getValue(planned.subscriptionKey),
                    name = planned.config.name,
                    protocol = planned.config.protocol.name,
                    address = planned.config.address,
                    port = planned.config.port,
                    configJson = json.encodeToString(planned.config),
                    sortOrder = planned.sortOrder,
                    edited = planned.edited,
                    guarded = planned.guarded,
                )
            }
            val serverIds = if (serverEntities.isEmpty()) emptyList() else serverDao.insertAll(serverEntities)
            serverIdByKey = plan.servers.map { it.key }.zip(serverIds).toMap()

            val routes = plan.appRoutes.map { planned ->
                AppRouteAssignment(
                    mode = planned.mode,
                    serverId = planned.serverKey?.let(serverIdByKey::getValue),
                    alwaysProxied = planned.alwaysProxied,
                ).toAppBypassEntity(
                    packageName = planned.packageName,
                    profileId = planned.profileId,
                    uid = 0,
                    manual = planned.manual,
                )
            }
            if (routes.isNotEmpty()) appBypassDao.insertAll(routes)
            selectedServerId = plan.selectedServerKey?.let(serverIdByKey::getValue) ?: -1L
        }

        settingsRepository.restoreFromMap(
            plan.source.settings + (LAST_SERVER_ID_SETTING to selectedServerId.toString()),
            sourceBackupVersion = plan.source.version,
        )
        val configOverride = plan.source.activeConfigOverride
        if (configOverride == null) {
            activeConfigOverrideStore.clear()
        } else {
            val newIdByOldId = serverIdByKey.mapNotNull { (key, newId) ->
                key.removePrefix(SERVER_KEY_PREFIX).toLongOrNull()?.let { oldId -> oldId to newId }
            }.toMap()
            check(activeConfigOverrideStore.save(remapServerTags(configOverride, newIdByOldId))) {
                "Unable to restore the edited runtime config"
            }
        }
        sections.forEach { section -> section.restore(plan.source.sections[section.key]) }
    }

    private suspend fun disconnectActiveConnection() {
        if (!connectionStateCoordinator.state.value.isRunning()) return
        connectionShutdown.forceDisconnect()
        checkNotNull(
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                connectionStateCoordinator.state.first { !it.isRunning() }
            },
        ) { "Timed out waiting for the active connection to stop" }
    }

    private suspend fun applyExternalSettings() {
        launcherIconSwitcher.apply(settingsRepository.launcherIcon.first())
        appUpdateScheduler.setEnabled(
            settingsRepository.appUpdateChecksEnabled.first(),
            settingsRepository.appUpdateInterval.first(),
        )
    }

    private suspend fun rollbackAfterFailure(previous: BackupData, original: Throwable) {
        runCatching {
            withContext(NonCancellable) {
                applyPlan(BackupImportPlanner.create(previous))
                applyExternalSettings()
                journalStore.delete()
            }
        }.exceptionOrNull()?.let(original::addSuppressed)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BACKUP_BYTES) throw IOException("The selected backup is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readBackup(locator: String): ByteArray {
        val input = backupStorage.openInput(locator)
            ?: throw IOException("Unable to open the selected backup")
        return input.use(::readLimited)
    }

    private fun decodeBackup(bytes: ByteArray): BackupData = try {
        json.decodeFromString(bytes.toString(Charsets.UTF_8))
    } catch (error: SerializationException) {
        throw IOException("The selected file is not a valid MaterialXray backup", error)
    }

    private fun createImportPlan(backup: BackupData): BackupImportPlan = try {
        BackupImportPlanner.create(backup)
    } catch (error: IllegalArgumentException) {
        throw IOException(error.message ?: "The backup is invalid", error)
    }

    private fun BackupImportPlan.toSummary() = BackupSummary(
        subscriptionCount = subscriptions.size,
        serverCount = servers.size,
        appRouteCount = appRoutes.size,
    )

    private fun ConnectionState.isRunning(): Boolean = when (this) {
        ConnectionState.Connecting,
        ConnectionState.ApplyingRoutingChanges,
        ConnectionState.UpdatingRoutingData,
        is ConnectionState.Connected,
        ConnectionState.Disconnecting,
        -> true

        ConnectionState.Disconnected,
        is ConnectionState.Error,
        is ConnectionState.InterfaceBusy,
        is ConnectionState.RestartRequired,
        -> false
    }

    private companion object {
        const val LAST_SERVER_ID_SETTING = "last_server_id"
        const val SERVER_KEY_PREFIX = "server-"
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        const val DISCONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

private val SERVER_TAG = Regex("\"(app-(?:in|proxy)-(?:forced-)?)(\\d+)\"")

/**
 * Points the per-app tags `AppRoutingPlanner` derives from server ids at the ids the servers got
 * on import, since a hand-edited config keeps the ones from when it was saved.
 */
internal fun remapServerTags(config: String, newIdByOldId: Map<Long, Long>): String = SERVER_TAG.replace(config) { match ->
    val newId = match.groupValues[2].toLongOrNull()?.let(newIdByOldId::get)
    if (newId == null) match.value else "\"${match.groupValues[1]}$newId\""
}
