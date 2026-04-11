# MaterialXray Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a root-based Android proxy app that uses xray-core's native TUN inbound with nftables traffic routing, Material Design 3 UI, and subscription URL support.

**Architecture:** The app runs xray-core as a root process with a TUN inbound. nftables rules route device traffic into the TUN interface while fwmark-based policy routing prevents loops. A persistent root shell session manages the xray lifecycle, network setup, and teardown. Jetpack Compose with MD3 provides the UI.

**Tech Stack:** Kotlin 2.3.20, AGP 9.1.0, Compose BOM 2026.03.00, Room 2.8.4, Hilt 2.56, KSP 2.3.6, kotlinx-serialization 1.11.0, OkHttp 5.3.0, xray-core v26.3.27

---

### Task 1: Project Scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/materialxray/MaterialXrayApp.kt`
- Create: `app/src/main/kotlin/com/materialxray/di/AppModule.kt`
- Create: `app/src/main/kotlin/com/materialxray/di/DatabaseModule.kt`
- Create: `app/src/main/kotlin/com/materialxray/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: Create Gradle wrapper and version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.1.0"
kotlin = "2.3.20"
ksp = "2.3.6"
compose-bom = "2026.03.00"
hilt = "2.56"
hilt-navigation-compose = "1.2.0"
room = "2.8.4"
datastore = "1.1.4"
navigation = "2.9.0"
lifecycle = "2.9.0"
okhttp = "5.3.0"
serialization = "1.11.0"
coroutines = "1.10.2"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }

activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.10.1" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }

hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hilt-navigation-compose" }

room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }

serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }

coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.16.0" }

junit = { group = "junit", name = "junit", version = "4.13.2" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MaterialXray"
include(":app")
```

Create `build.gradle.kts` (root):

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 2: Create app build.gradle.kts**

Create `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.materialxray"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.materialxray"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 3: Create AndroidManifest.xml**

Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

    <application
        android:name=".MaterialXrayApp"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.MaterialXray"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.MaterialXray">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.XrayService"
            android:foregroundServiceType="specialUse"
            android:exported="false" />

        <receiver
            android:name=".service.BootReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

- [ ] **Step 4: Create Application class, DI modules, and MainActivity stub**

Create `app/src/main/kotlin/com/materialxray/MaterialXrayApp.kt`:

```kotlin
package com.materialxray

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MaterialXrayApp : Application()
```

Create `app/src/main/kotlin/com/materialxray/di/AppModule.kt`:

```kotlin
package com.materialxray.di

import android.content.Context
import com.materialxray.core.root.RootShell
import com.materialxray.data.parser.SubscriptionFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRootShell(): RootShell = RootShell()

    @Provides
    @Singleton
    fun provideSubscriptionFetcher(client: OkHttpClient): SubscriptionFetcher =
        SubscriptionFetcher(client)
}
```

Create `app/src/main/kotlin/com/materialxray/di/DatabaseModule.kt`:

```kotlin
package com.materialxray.di

import android.content.Context
import androidx.room.Room
import com.materialxray.data.db.AppDatabase
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "materialxray.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideSubscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideAppBypassDao(db: AppDatabase): AppBypassDao = db.appBypassDao()
}
```

Create `app/src/main/kotlin/com/materialxray/MainActivity.kt`:

```kotlin
package com.materialxray

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.materialxray.ui.navigation.MainNavigation
import com.materialxray.ui.theme.MaterialXrayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialXrayTheme {
                MainNavigation()
            }
        }
    }
}
```

Create `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">MaterialXray</string>
</resources>
```

Create `app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MaterialXray" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 5: Initialize Gradle wrapper**

Run:
```bash
cd /home/aether/Projects/MaterialXray
gradle wrapper --gradle-version 9.1
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: scaffold Android project with Compose, Hilt, Room, KSP"
```

---

### Task 2: Data Models

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/model/Protocol.kt`
- Create: `app/src/main/kotlin/com/materialxray/model/ServerConfig.kt`
- Create: `app/src/main/kotlin/com/materialxray/model/ConnectionState.kt`
- Create: `app/src/main/kotlin/com/materialxray/model/BackupData.kt`

- [ ] **Step 1: Create Protocol enum**

Create `app/src/main/kotlin/com/materialxray/model/Protocol.kt`:

```kotlin
package com.materialxray.model

import kotlinx.serialization.Serializable

@Serializable
enum class Protocol(val displayName: String, val scheme: String) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss");

    companion object {
        fun fromScheme(scheme: String): Protocol? =
            entries.find { it.scheme == scheme.lowercase() }
    }
}
```

- [ ] **Step 2: Create ServerConfig**

Create `app/src/main/kotlin/com/materialxray/model/ServerConfig.kt`:

```kotlin
package com.materialxray.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val protocol: Protocol,
    val name: String,
    val address: String,
    val port: Int,
    val password: String, // UUID for VLESS/VMess, password for Trojan/SS
    val transport: Transport = Transport(),
    val security: Security = Security(),
    val extra: Map<String, String> = emptyMap(),
    val rawUri: String = "",
) {
    @Serializable
    data class Transport(
        val type: String = "tcp", // tcp, ws, grpc, xhttp, httpupgrade
        val path: String = "",
        val host: String = "",
        val serviceName: String = "", // gRPC
        val mode: String = "", // xhttp mode
    )

    @Serializable
    data class Security(
        val type: String = "none", // none, tls, reality
        val sni: String = "",
        val fingerprint: String = "",
        val alpn: List<String> = emptyList(),
        val publicKey: String = "", // REALITY pbk
        val shortId: String = "", // REALITY sid
    )
}
```

- [ ] **Step 3: Create ConnectionState**

Create `app/src/main/kotlin/com/materialxray/model/ConnectionState.kt`:

```kotlin
package com.materialxray.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(
        val serverName: String,
        val startTime: Long = System.currentTimeMillis(),
    ) : ConnectionState
    data object Disconnecting : ConnectionState
    data class Error(val message: String) : ConnectionState
}
```

- [ ] **Step 4: Create BackupData**

Create `app/src/main/kotlin/com/materialxray/model/BackupData.kt`:

```kotlin
package com.materialxray.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: Int = 1,
    val subscriptions: List<BackupSubscription>,
    val servers: List<BackupServer>,
    val bypassedApps: List<String>, // package names
    val settings: Map<String, String>,
) {
    @Serializable
    data class BackupSubscription(
        val name: String,
        val url: String,
    )

    @Serializable
    data class BackupServer(
        val subscriptionUrl: String?,
        val config: ServerConfig,
    )
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/model/
git commit -m "feat: add data models — Protocol, ServerConfig, ConnectionState, BackupData"
```

---

### Task 3: Room Database

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/data/db/entity/ServerEntity.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/entity/SubscriptionEntity.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/entity/AppBypassEntity.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/dao/ServerDao.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/dao/SubscriptionDao.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/dao/AppBypassDao.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/Converters.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/db/AppDatabase.kt`

- [ ] **Step 1: Create entities**

Create `app/src/main/kotlin/com/materialxray/data/db/entity/SubscriptionEntity.kt`:

```kotlin
package com.materialxray.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0,
)
```

Create `app/src/main/kotlin/com/materialxray/data/db/entity/ServerEntity.kt`:

```kotlin
package com.materialxray.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "servers",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("subscriptionId")],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val configJson: String, // Serialized ServerConfig
    val latencyMs: Int = -1, // -1 = untested
    val sortOrder: Int = 0,
)
```

Create `app/src/main/kotlin/com/materialxray/data/db/entity/AppBypassEntity.kt`:

```kotlin
package com.materialxray.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_bypass")
data class AppBypassEntity(
    @PrimaryKey val packageName: String,
    val uid: Int,
    val excluded: Boolean = true,
)
```

- [ ] **Step 2: Create DAOs**

Create `app/src/main/kotlin/com/materialxray/data/db/dao/SubscriptionDao.kt`:

```kotlin
package com.materialxray.data.db.dao

import androidx.room.*
import com.materialxray.data.db.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY id")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Insert
    suspend fun insert(sub: SubscriptionEntity): Long

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Delete
    suspend fun delete(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}
```

Create `app/src/main/kotlin/com/materialxray/data/db/dao/ServerDao.kt`:

```kotlin
package com.materialxray.data.db.dao

import androidx.room.*
import com.materialxray.data.db.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY subscriptionId, sortOrder")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder")
    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Insert
    suspend fun insertAll(servers: List<ServerEntity>)

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    @Query("UPDATE servers SET latencyMs = :latency WHERE id = :id")
    suspend fun updateLatency(id: Long, latency: Int)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}
```

Create `app/src/main/kotlin/com/materialxray/data/db/dao/AppBypassDao.kt`:

```kotlin
package com.materialxray.data.db.dao

import androidx.room.*
import com.materialxray.data.db.entity.AppBypassEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBypassDao {
    @Query("SELECT * FROM app_bypass ORDER BY packageName")
    fun observeAll(): Flow<List<AppBypassEntity>>

    @Query("SELECT * FROM app_bypass WHERE excluded = 1")
    suspend fun getExcluded(): List<AppBypassEntity>

    @Upsert
    suspend fun upsert(entity: AppBypassEntity)

    @Query("DELETE FROM app_bypass WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM app_bypass")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AppBypassEntity>)
}
```

- [ ] **Step 3: Create database class**

Create `app/src/main/kotlin/com/materialxray/data/db/AppDatabase.kt`:

```kotlin
package com.materialxray.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.AppBypassEntity
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, AppBypassEntity::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun appBypassDao(): AppBypassDao
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/data/db/
git commit -m "feat: add Room database — entities, DAOs, AppDatabase"
```

---

### Task 4: DataStore Settings Repository

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/data/repository/SettingsRepository.kt`

- [ ] **Step 1: Create SettingsRepository**

Create `app/src/main/kotlin/com/materialxray/data/repository/SettingsRepository.kt`:

```kotlin
package com.materialxray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.dataStore

    companion object {
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val FWMARK = intPreferencesKey("fwmark")
        val ROUTE_TABLE = intPreferencesKey("route_table")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val LAST_SERVER_ID = longPreferencesKey("last_server_id")
        val XRAY_VERSION = stringPreferencesKey("xray_version")
    }

    val tunName: Flow<String> = store.data.map { it[TUN_NAME] ?: "xray0" }
    val dnsServers: Flow<String> = store.data.map { it[DNS_SERVERS] ?: "1.1.1.1,8.8.8.8" }
    val fwmark: Flow<Int> = store.data.map { it[FWMARK] ?: 255 }
    val routeTable: Flow<Int> = store.data.map { it[ROUTE_TABLE] ?: 100 }
    val autoConnect: Flow<Boolean> = store.data.map { it[AUTO_CONNECT] ?: false }
    val lastServerId: Flow<Long> = store.data.map { it[LAST_SERVER_ID] ?: -1L }

    suspend fun setTunName(name: String) = store.edit { it[TUN_NAME] = name }
    suspend fun setDnsServers(servers: String) = store.edit { it[DNS_SERVERS] = servers }
    suspend fun setFwmark(mark: Int) = store.edit { it[FWMARK] = mark }
    suspend fun setRouteTable(table: Int) = store.edit { it[ROUTE_TABLE] = table }
    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[AUTO_CONNECT] = enabled }
    suspend fun setLastServerId(id: Long) = store.edit { it[LAST_SERVER_ID] = id }

    suspend fun getAllAsMap(): Map<String, String> {
        val prefs = store.data.map { preferences ->
            preferences.asMap().entries.associate { (k, v) -> k.name to v.toString() }
        }
        var result = emptyMap<String, String>()
        prefs.collect { result = it; return@collect }
        return result
    }

    suspend fun restoreFromMap(map: Map<String, String>) {
        store.edit { prefs ->
            prefs.clear()
            map["tun_name"]?.let { prefs[TUN_NAME] = it }
            map["dns_servers"]?.let { prefs[DNS_SERVERS] = it }
            map["fwmark"]?.let { prefs[FWMARK] = it.toIntOrNull() ?: 255 }
            map["route_table"]?.let { prefs[ROUTE_TABLE] = it.toIntOrNull() ?: 100 }
            map["auto_connect"]?.let { prefs[AUTO_CONNECT] = it.toBooleanStrictOrNull() ?: false }
            map["last_server_id"]?.let { prefs[LAST_SERVER_ID] = it.toLongOrNull() ?: -1L }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/data/repository/SettingsRepository.kt
git commit -m "feat: add DataStore SettingsRepository"
```

---

### Task 5: Share Link Parsers (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/data/parser/VlessParser.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/parser/VmessParser.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/parser/TrojanParser.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/parser/ShadowsocksParser.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/parser/ShareLinkParser.kt`
- Create: `app/src/test/kotlin/com/materialxray/data/parser/ShareLinkParserTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/com/materialxray/data/parser/ShareLinkParserTest.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.Protocol
import org.junit.Assert.*
import org.junit.Test

class ShareLinkParserTest {

    private val parser = ShareLinkParser()

    @Test
    fun `parse VLESS REALITY link`() {
        val uri = "vless://fe98768f-8ff3-4cc1-a8bd-235dd4856422@finland.bcvpn.rknotso.site:443" +
            "?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality" +
            "&sni=o-zone.ai&fp=chrome&pbk=KgCgITTjjYYKWCACqo-ELmMs3aoI3Ya4XHOUjqcIgT4" +
            "#%F0%9F%87%AB%F0%9F%87%AE%20Finland"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.VLESS, config.protocol)
        assertEquals("finland.bcvpn.rknotso.site", config.address)
        assertEquals(443, config.port)
        assertEquals("fe98768f-8ff3-4cc1-a8bd-235dd4856422", config.password)
        assertEquals("tcp", config.transport.type)
        assertEquals("reality", config.security.type)
        assertEquals("o-zone.ai", config.security.sni)
        assertEquals("chrome", config.security.fingerprint)
        assertEquals("KgCgITTjjYYKWCACqo-ELmMs3aoI3Ya4XHOUjqcIgT4", config.security.publicKey)
        assertEquals("xtls-rprx-vision", config.extra["flow"])
        assertTrue(config.name.contains("Finland"))
    }

    @Test
    fun `parse VLESS xhttp link`() {
        val uri = "vless://fe98768f-8ff3-4cc1-a8bd-235dd4856422@176.108.252.54:443" +
            "?encryption=none&type=xhttp&path=%2Fapi%2Fv1&host=invest-helper.ru&mode=auto" +
            "&security=reality&sni=invest-helper.ru&fp=chrome" +
            "&pbk=GIaeJdOncW-5-blZTWOovjP5yBNiO-JLRGpQfgOt5CI" +
            "#Test%20Server"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals("xhttp", config.transport.type)
        assertEquals("/api/v1", config.transport.path)
        assertEquals("invest-helper.ru", config.transport.host)
        assertEquals("auto", config.transport.mode)
    }

    @Test
    fun `parse VMess base64 link`() {
        val json = """{"v":"2","ps":"Tokyo","add":"1.2.3.4","port":"443","id":"abc-def","aid":"0","net":"ws","type":"none","host":"example.com","path":"/ws","tls":"tls","sni":"example.com"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val uri = "vmess://$encoded"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.VMESS, config.protocol)
        assertEquals("Tokyo", config.name)
        assertEquals("1.2.3.4", config.address)
        assertEquals(443, config.port)
        assertEquals("abc-def", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/ws", config.transport.path)
        assertEquals("example.com", config.transport.host)
        assertEquals("tls", config.security.type)
    }

    @Test
    fun `parse Trojan link`() {
        val uri = "trojan://mypassword@server.example.com:443?security=tls&sni=server.example.com&type=ws&path=%2Ftrojan#MyServer"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.TROJAN, config.protocol)
        assertEquals("server.example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("mypassword", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/trojan", config.transport.path)
        assertEquals("tls", config.security.type)
        assertEquals("MyServer", config.name)
    }

    @Test
    fun `parse Shadowsocks SIP002 link`() {
        val methodPassword = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:testpassword".toByteArray())
        val uri = "ss://${methodPassword}@1.2.3.4:8388#SS%20Server"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.SHADOWSOCKS, config.protocol)
        assertEquals("1.2.3.4", config.address)
        assertEquals(8388, config.port)
        assertEquals("testpassword", config.password)
        assertEquals("aes-256-gcm", config.extra["method"])
        assertEquals("SS Server", config.name)
    }

    @Test
    fun `parse unknown scheme returns null`() {
        assertNull(parser.parse("http://example.com"))
    }

    @Test
    fun `parse malformed URI returns null`() {
        assertNull(parser.parse("vless://not-valid"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.materialxray.data.parser.ShareLinkParserTest" 2>&1 | tail -20`
Expected: Compilation error — classes don't exist yet.

- [ ] **Step 3: Implement parsers**

Create `app/src/main/kotlin/com/materialxray/data/parser/VlessParser.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import java.net.URI
import java.net.URLDecoder

object VlessParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val stripped = uri.removePrefix("vless://")
        val parsed = URI("vless://$stripped")
        val userInfo = parsed.rawUserInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val fragment = parsed.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        val params = parseQuery(parsed.rawQuery ?: "")

        ServerConfig(
            protocol = Protocol.VLESS,
            name = fragment,
            address = host,
            port = port,
            password = userInfo,
            transport = ServerConfig.Transport(
                type = params["type"] ?: "tcp",
                path = params["path"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "",
                host = params["host"] ?: "",
                serviceName = params["serviceName"] ?: "",
                mode = params["mode"] ?: "",
            ),
            security = ServerConfig.Security(
                type = params["security"] ?: "none",
                sni = params["sni"] ?: "",
                fingerprint = params["fp"] ?: "",
                alpn = params["alpn"]?.split(",") ?: emptyList(),
                publicKey = params["pbk"] ?: "",
                shortId = params["sid"] ?: "",
            ),
            extra = buildMap {
                params["encryption"]?.let { put("encryption", it) }
                params["flow"]?.let { put("flow", it) }
            },
            rawUri = uri,
        )
    }.getOrNull()
}

internal fun parseQuery(query: String): Map<String, String> =
    query.split("&")
        .filter { it.contains("=") }
        .associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
```

Create `app/src/main/kotlin/com/materialxray/data/parser/VmessParser.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import kotlinx.serialization.json.*
import java.util.Base64

object VmessParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val encoded = uri.removePrefix("vmess://")
        val decoded = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        val json = Json.parseToJsonElement(decoded).jsonObject

        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull ?: ""

        val port = str("port").toIntOrNull() ?: return null
        val address = str("add").ifEmpty { return null }

        val net = str("net")
        val tls = str("tls")

        ServerConfig(
            protocol = Protocol.VMESS,
            name = str("ps"),
            address = address,
            port = port,
            password = str("id"),
            transport = ServerConfig.Transport(
                type = net.ifEmpty { "tcp" },
                path = str("path"),
                host = str("host"),
                serviceName = str("serviceName"),
            ),
            security = ServerConfig.Security(
                type = tls.ifEmpty { "none" },
                sni = str("sni"),
                fingerprint = str("fp"),
                alpn = str("alpn").let { if (it.isNotEmpty()) it.split(",") else emptyList() },
            ),
            extra = buildMap {
                str("aid").takeIf { it.isNotEmpty() }?.let { put("alterId", it) }
            },
            rawUri = uri,
        )
    }.getOrNull()
}
```

Create `app/src/main/kotlin/com/materialxray/data/parser/TrojanParser.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import java.net.URI
import java.net.URLDecoder

object TrojanParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val parsed = URI(uri)
        val password = parsed.rawUserInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val fragment = parsed.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        val params = parseQuery(parsed.rawQuery ?: "")

        ServerConfig(
            protocol = Protocol.TROJAN,
            name = fragment,
            address = host,
            port = port,
            password = URLDecoder.decode(password, "UTF-8"),
            transport = ServerConfig.Transport(
                type = params["type"] ?: "tcp",
                path = params["path"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "",
                host = params["host"] ?: "",
                serviceName = params["serviceName"] ?: "",
            ),
            security = ServerConfig.Security(
                type = params["security"] ?: "tls",
                sni = params["sni"] ?: "",
                fingerprint = params["fp"] ?: "",
                alpn = params["alpn"]?.split(",") ?: emptyList(),
            ),
            rawUri = uri,
        )
    }.getOrNull()
}
```

Create `app/src/main/kotlin/com/materialxray/data/parser/ShadowsocksParser.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

object ShadowsocksParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val stripped = uri.removePrefix("ss://")
        val fragmentIdx = stripped.indexOf('#')
        val name = if (fragmentIdx >= 0) {
            URLDecoder.decode(stripped.substring(fragmentIdx + 1), "UTF-8")
        } else ""
        val main = if (fragmentIdx >= 0) stripped.substring(0, fragmentIdx) else stripped

        // SIP002 format: base64(method:password)@host:port
        val atIdx = main.lastIndexOf('@')
        if (atIdx < 0) return parseLegacy(main, name, uri)

        val userInfo = main.substring(0, atIdx)
        val hostPort = main.substring(atIdx + 1)

        val decoded = try {
            Base64.getUrlDecoder().decode(userInfo).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            Base64.getDecoder().decode(userInfo).toString(Charsets.UTF_8)
        }

        val colonIdx = decoded.indexOf(':')
        if (colonIdx < 0) return null
        val method = decoded.substring(0, colonIdx)
        val password = decoded.substring(colonIdx + 1)

        val parsed = URI("ss://x@$hostPort")
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null

        ServerConfig(
            protocol = Protocol.SHADOWSOCKS,
            name = name,
            address = host,
            port = port,
            password = password,
            extra = mapOf("method" to method),
            rawUri = uri,
        )
    }.getOrNull()

    private fun parseLegacy(encoded: String, name: String, rawUri: String): ServerConfig? =
        runCatching {
            val decoded = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            // method:password@host:port
            val atIdx = decoded.lastIndexOf('@')
            if (atIdx < 0) return null
            val methodPassword = decoded.substring(0, atIdx)
            val hostPort = decoded.substring(atIdx + 1)
            val colonIdx = methodPassword.indexOf(':')
            if (colonIdx < 0) return null
            val method = methodPassword.substring(0, colonIdx)
            val password = methodPassword.substring(colonIdx + 1)
            val parsed = URI("ss://x@$hostPort")

            ServerConfig(
                protocol = Protocol.SHADOWSOCKS,
                name = name,
                address = parsed.host ?: return null,
                port = parsed.port.takeIf { it > 0 } ?: return null,
                password = password,
                extra = mapOf("method" to method),
                rawUri = rawUri,
            )
        }.getOrNull()
}
```

Create `app/src/main/kotlin/com/materialxray/data/parser/ShareLinkParser.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.ServerConfig

class ShareLinkParser {

    fun parse(uri: String): ServerConfig? {
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("vless://") -> VlessParser.parse(trimmed)
            trimmed.startsWith("vmess://") -> VmessParser.parse(trimmed)
            trimmed.startsWith("trojan://") -> TrojanParser.parse(trimmed)
            trimmed.startsWith("ss://") -> ShadowsocksParser.parse(trimmed)
            else -> null
        }
    }

    fun parseMultiple(text: String): List<ServerConfig> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.materialxray.data.parser.ShareLinkParserTest" 2>&1 | tail -20`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/data/parser/ app/src/test/kotlin/com/materialxray/data/parser/
git commit -m "feat: add share link parsers — VLESS, VMess, Trojan, Shadowsocks with tests"
```

---

### Task 6: Subscription Fetcher

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/data/parser/SubscriptionFetcher.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/repository/ServerRepository.kt`
- Create: `app/src/main/kotlin/com/materialxray/data/repository/SubscriptionRepository.kt`

- [ ] **Step 1: Create SubscriptionFetcher**

Create `app/src/main/kotlin/com/materialxray/data/parser/SubscriptionFetcher.kt`:

```kotlin
package com.materialxray.data.parser

import com.materialxray.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import javax.inject.Inject

class SubscriptionFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    private val parser = ShareLinkParser()

    suspend fun fetch(url: String): List<ServerConfig> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()

        // Try base64 decode first, fall back to raw text
        val decoded = try {
            Base64.getDecoder().decode(body.trim()).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            body
        }

        parser.parseMultiple(decoded)
    }
}
```

- [ ] **Step 2: Create repositories**

Create `app/src/main/kotlin/com/materialxray/data/repository/SubscriptionRepository.kt`:

```kotlin
package com.materialxray.data.repository

import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity
import com.materialxray.data.parser.SubscriptionFetcher
import com.materialxray.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val fetcher: SubscriptionFetcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()

    suspend fun add(name: String, url: String): Long {
        val id = subscriptionDao.insert(SubscriptionEntity(name = name, url = url))
        refresh(id, url)
        return id
    }

    suspend fun refresh(subId: Long, url: String) {
        val configs = fetcher.fetch(url)
        serverDao.deleteBySubscription(subId)
        serverDao.insertAll(configs.mapIndexed { index, config ->
            ServerEntity(
                subscriptionId = subId,
                name = config.name,
                protocol = config.protocol.name,
                address = config.address,
                port = config.port,
                configJson = json.encodeToString(config),
                sortOrder = index,
            )
        })
        subscriptionDao.getById(subId)?.let {
            subscriptionDao.update(it.copy(lastUpdated = System.currentTimeMillis()))
        }
    }

    suspend fun refreshAll() {
        subscriptionDao.getAll().forEach { sub ->
            runCatching { refresh(sub.id, sub.url) }
        }
    }

    suspend fun delete(sub: SubscriptionEntity) {
        subscriptionDao.delete(sub)
    }
}
```

Create `app/src/main/kotlin/com/materialxray/data/repository/ServerRepository.kt`:

```kotlin
package com.materialxray.data.repository

import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<ServerEntity>> = serverDao.observeAll()

    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>> =
        serverDao.observeBySubscription(subId)

    suspend fun getById(id: Long): ServerEntity? = serverDao.getById(id)

    fun parseConfig(entity: ServerEntity): ServerConfig =
        json.decodeFromString(entity.configJson)

    suspend fun updateLatency(id: Long, latencyMs: Int) {
        serverDao.updateLatency(id, latencyMs)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/data/
git commit -m "feat: add SubscriptionFetcher and repositories"
```

---

### Task 7: Xray Config Generator (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/core/xray/ConfigGenerator.kt`
- Create: `app/src/test/kotlin/com/materialxray/core/xray/ConfigGeneratorTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/com/materialxray/core/xray/ConfigGeneratorTest.kt`:

```kotlin
package com.materialxray.core.xray

import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigGeneratorTest {

    private val generator = ConfigGenerator()

    private val vlessReality = ServerConfig(
        protocol = Protocol.VLESS,
        name = "Test",
        address = "1.2.3.4",
        port = 443,
        password = "test-uuid",
        transport = ServerConfig.Transport(type = "tcp"),
        security = ServerConfig.Security(
            type = "reality",
            sni = "example.com",
            fingerprint = "chrome",
            publicKey = "testpbk",
            shortId = "",
        ),
        extra = mapOf("flow" to "xtls-rprx-vision", "encryption" to "none"),
    )

    @Test
    fun `generates TUN inbound with correct name and MTU`() {
        val config = generator.generate(vlessReality, tunName = "wlan2", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val inbounds = json["inbounds"]!!.jsonArray
        val tun = inbounds.first { it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun" }.jsonObject
        assertEquals(0, tun["port"]?.jsonPrimitive?.int)
        val settings = tun["settings"]!!.jsonObject
        assertEquals("wlan2", settings["name"]?.jsonPrimitive?.content)
        assertEquals(1500, settings["MTU"]?.jsonPrimitive?.int)
    }

    @Test
    fun `sets fwmark on all outbounds`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val outbounds = json["outbounds"]!!.jsonArray
        for (ob in outbounds) {
            val mark = ob.jsonObject["streamSettings"]?.jsonObject
                ?.get("sockopt")?.jsonObject
                ?.get("mark")?.jsonPrimitive?.int
            assertEquals("All outbounds must have fwmark", 255, mark)
        }
    }

    @Test
    fun `generates VLESS REALITY outbound correctly`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val outbounds = json["outbounds"]!!.jsonArray
        val proxy = outbounds.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy"
        }.jsonObject
        assertEquals("vless", proxy["protocol"]?.jsonPrimitive?.content)
        val vnext = proxy["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject
        assertEquals("1.2.3.4", vnext["address"]?.jsonPrimitive?.content)
        assertEquals(443, vnext["port"]?.jsonPrimitive?.int)
        val user = vnext["users"]!!.jsonArray[0].jsonObject
        assertEquals("test-uuid", user["id"]?.jsonPrimitive?.content)
        assertEquals("none", user["encryption"]?.jsonPrimitive?.content)
        assertEquals("xtls-rprx-vision", user["flow"]?.jsonPrimitive?.content)
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("reality", stream["security"]?.jsonPrimitive?.content)
        val reality = stream["realitySettings"]!!.jsonObject
        assertEquals("example.com", reality["serverName"]?.jsonPrimitive?.content)
        assertEquals("chrome", reality["fingerprint"]?.jsonPrimitive?.content)
        assertEquals("testpbk", reality["publicKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `includes DNS section with port 53 routing`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255, dnsServers = "1.1.1.1,8.8.8.8")
        val json = Json.parseToJsonElement(config).jsonObject
        assertNotNull(json["dns"])
        val routing = json["routing"]!!.jsonObject
        val rules = routing["rules"]!!.jsonArray
        val dnsRule = rules.any { rule ->
            rule.jsonObject["port"]?.jsonPrimitive?.content == "53"
        }
        assertTrue("Should have DNS port 53 routing rule", dnsRule)
    }

    @Test
    fun `generates VMess outbound`() {
        val vmess = ServerConfig(
            protocol = Protocol.VMESS,
            name = "Test VMess",
            address = "5.6.7.8",
            port = 443,
            password = "vmess-uuid",
            transport = ServerConfig.Transport(type = "ws", path = "/ws", host = "example.com"),
            security = ServerConfig.Security(type = "tls", sni = "example.com"),
            extra = mapOf("alterId" to "0"),
        )
        val config = generator.generate(vmess, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val proxy = json["outbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy"
        }.jsonObject
        assertEquals("vmess", proxy["protocol"]?.jsonPrimitive?.content)
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("ws", stream["network"]?.jsonPrimitive?.content)
        val wsSettings = stream["wsSettings"]!!.jsonObject
        assertEquals("/ws", wsSettings["path"]?.jsonPrimitive?.content)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.materialxray.core.xray.ConfigGeneratorTest" 2>&1 | tail -10`
Expected: Compilation error — ConfigGenerator doesn't exist.

- [ ] **Step 3: Implement ConfigGenerator**

Create `app/src/main/kotlin/com/materialxray/core/xray/ConfigGenerator.kt`:

```kotlin
package com.materialxray.core.xray

import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import kotlinx.serialization.json.*

class ConfigGenerator {

    fun generate(
        server: ServerConfig,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,8.8.8.8",
    ): String {
        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", "warning")
            })
            put("dns", buildDns(dnsServers))
            put("inbounds", buildJsonArray {
                add(buildTunInbound(tunName))
            })
            put("outbounds", buildJsonArray {
                add(buildProxyOutbound(server, fwmark))
                add(buildDirectOutbound(fwmark))
                add(buildDnsOutbound(fwmark))
            })
            put("routing", buildRouting())
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config)
    }

    fun injectTunIntoRawConfig(
        rawJson: String,
        tunName: String = "xray0",
        fwmark: Int = 255,
    ): String {
        val original = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()

        // Add TUN inbound
        val existingInbounds = original["inbounds"]?.jsonArray?.toMutableList() ?: mutableListOf()
        existingInbounds.add(0, buildTunInbound(tunName))
        original["inbounds"] = JsonArray(existingInbounds)

        // Add fwmark to all outbounds
        val outbounds = original["outbounds"]?.jsonArray?.map { ob ->
            val obj = ob.jsonObject.toMutableMap()
            val stream = obj["streamSettings"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
            stream["sockopt"] = buildJsonObject { put("mark", fwmark) }
            obj["streamSettings"] = JsonObject(stream)
            JsonObject(obj)
        } ?: emptyList()
        original["outbounds"] = JsonArray(outbounds)

        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(original))
    }

    private fun buildTunInbound(tunName: String) = buildJsonObject {
        put("port", 0)
        put("protocol", "tun")
        put("settings", buildJsonObject {
            put("name", tunName)
            put("MTU", 1500)
        })
        put("tag", "tun-in")
    }

    private fun buildProxyOutbound(server: ServerConfig, fwmark: Int) = buildJsonObject {
        put("tag", "proxy")
        put("protocol", server.protocol.scheme)
        put("settings", buildOutboundSettings(server))
        put("streamSettings", buildStreamSettings(server, fwmark))
    }

    private fun buildOutboundSettings(server: ServerConfig): JsonObject = when (server.protocol) {
        Protocol.VLESS -> buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", server.password)
                            put("encryption", server.extra["encryption"] ?: "none")
                            server.extra["flow"]?.let { put("flow", it) }
                        })
                    })
                })
            })
        }

        Protocol.VMESS -> buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", server.password)
                            put("alterId", server.extra["alterId"]?.toIntOrNull() ?: 0)
                            put("security", "auto")
                        })
                    })
                })
            })
        }

        Protocol.TROJAN -> buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    put("password", server.password)
                })
            })
        }

        Protocol.SHADOWSOCKS -> buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    put("method", server.extra["method"] ?: "aes-256-gcm")
                    put("password", server.password)
                })
            })
        }
    }

    private fun buildStreamSettings(server: ServerConfig, fwmark: Int) = buildJsonObject {
        put("network", server.transport.type)
        put("security", server.security.type)

        put("sockopt", buildJsonObject {
            put("mark", fwmark)
        })

        when (server.security.type) {
            "tls" -> put("tlsSettings", buildJsonObject {
                if (server.security.sni.isNotEmpty()) put("serverName", server.security.sni)
                if (server.security.fingerprint.isNotEmpty()) put("fingerprint", server.security.fingerprint)
                if (server.security.alpn.isNotEmpty()) put("alpn", buildJsonArray {
                    server.security.alpn.forEach { add(it) }
                })
            })
            "reality" -> put("realitySettings", buildJsonObject {
                if (server.security.sni.isNotEmpty()) put("serverName", server.security.sni)
                if (server.security.fingerprint.isNotEmpty()) put("fingerprint", server.security.fingerprint)
                if (server.security.publicKey.isNotEmpty()) put("publicKey", server.security.publicKey)
                if (server.security.shortId.isNotEmpty()) put("shortId", server.security.shortId)
            })
        }

        when (server.transport.type) {
            "ws" -> put("wsSettings", buildJsonObject {
                if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
                if (server.transport.host.isNotEmpty()) put("headers", buildJsonObject {
                    put("Host", server.transport.host)
                })
            })
            "grpc" -> put("grpcSettings", buildJsonObject {
                if (server.transport.serviceName.isNotEmpty()) put("serviceName", server.transport.serviceName)
            })
            "xhttp" -> put("xhttpSettings", buildJsonObject {
                if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
                if (server.transport.host.isNotEmpty()) put("host", server.transport.host)
                if (server.transport.mode.isNotEmpty()) put("mode", server.transport.mode)
            })
            "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
                if (server.transport.host.isNotEmpty()) put("host", server.transport.host)
            })
        }
    }

    private fun buildDirectOutbound(fwmark: Int) = buildJsonObject {
        put("tag", "direct")
        put("protocol", "freedom")
        put("settings", buildJsonObject {})
        put("streamSettings", buildJsonObject {
            put("sockopt", buildJsonObject {
                put("mark", fwmark)
            })
        })
    }

    private fun buildDnsOutbound(fwmark: Int) = buildJsonObject {
        put("tag", "dns-out")
        put("protocol", "dns")
        put("settings", buildJsonObject {})
        put("streamSettings", buildJsonObject {
            put("sockopt", buildJsonObject {
                put("mark", fwmark)
            })
        })
    }

    private fun buildDns(servers: String) = buildJsonObject {
        put("servers", buildJsonArray {
            servers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        })
    }

    private fun buildRouting() = buildJsonObject {
        put("domainStrategy", "AsIs")
        put("rules", buildJsonArray {
            // DNS hijack
            add(buildJsonObject {
                put("type", "field")
                put("port", "53")
                put("outboundTag", "dns-out")
            })
            // Private IPs direct
            add(buildJsonObject {
                put("type", "field")
                put("ip", buildJsonArray {
                    add("geoip:private")
                })
                put("outboundTag", "direct")
            })
            // Everything else through proxy
            add(buildJsonObject {
                put("type", "field")
                put("port", "0-65535")
                put("outboundTag", "proxy")
            })
        })
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.materialxray.core.xray.ConfigGeneratorTest" 2>&1 | tail -20`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/core/xray/ app/src/test/kotlin/com/materialxray/core/xray/
git commit -m "feat: add xray ConfigGenerator with TUN inbound, fwmark, and protocol support"
```

---

### Task 8: Root Shell

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/core/root/RootShell.kt`

- [ ] **Step 1: Implement RootShell**

Create `app/src/main/kotlin/com/materialxray/core/root/RootShell.kt`:

```kotlin
package com.materialxray.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter

class RootShell {
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private var stderr: BufferedReader? = null
    private val mutex = Mutex()

    data class Result(
        val exitCode: Int,
        val output: String,
        val error: String,
    ) {
        val isSuccess get() = exitCode == 0
    }

    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (process != null) return@withContext true
            runCatching {
                val p = ProcessBuilder("su").redirectErrorStream(false).start()
                process = p
                stdin = OutputStreamWriter(p.outputStream)
                stdout = p.inputStream.bufferedReader()
                stderr = p.errorStream.bufferedReader()
                // Test with a simple command
                executeInternal("id").isSuccess
            }.getOrElse {
                close()
                false
            }
        }
    }

    suspend fun execute(command: String): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (process == null && !open()) {
                return@withContext Result(-1, "", "Root shell not available")
            }
            executeInternal(command)
        }
    }

    private fun executeInternal(command: String): Result {
        val writer = stdin ?: return Result(-1, "", "Shell closed")
        val reader = stdout ?: return Result(-1, "", "Shell closed")

        val marker = "XRAY_CMD_DONE_${System.nanoTime()}"
        val exitMarker = "XRAY_EXIT_${System.nanoTime()}"

        writer.write("$command\n")
        writer.write("echo $exitMarker\\$?\n")
        writer.write("echo $marker\n")
        writer.flush()

        val outputLines = mutableListOf<String>()
        var exitCode = -1

        while (true) {
            val line = reader.readLine() ?: break
            if (line == marker) break
            if (line.startsWith(exitMarker)) {
                exitCode = line.removePrefix(exitMarker).toIntOrNull() ?: -1
            } else {
                outputLines.add(line)
            }
        }

        // Drain stderr non-blocking
        val errorOutput = buildString {
            val errReader = stderr ?: return@buildString
            while (errReader.ready()) {
                append(errReader.readLine())
                append('\n')
            }
        }

        return Result(exitCode, outputLines.joinToString("\n"), errorOutput.trimEnd())
    }

    fun close() {
        runCatching {
            stdin?.write("exit\n")
            stdin?.flush()
        }
        runCatching { process?.destroy() }
        process = null
        stdin = null
        stdout = null
        stderr = null
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/core/root/
git commit -m "feat: add RootShell — persistent su session with command execution"
```

---

### Task 9: Network Layer — NftablesManager, TunManager, CleanupManager

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/core/nftables/NftablesManager.kt`
- Create: `app/src/main/kotlin/com/materialxray/core/xray/TunManager.kt`
- Create: `app/src/main/kotlin/com/materialxray/core/xray/StateFile.kt`
- Create: `app/src/main/kotlin/com/materialxray/core/xray/CleanupManager.kt`

- [ ] **Step 1: Create NftablesManager**

Create `app/src/main/kotlin/com/materialxray/core/nftables/NftablesManager.kt`:

```kotlin
package com.materialxray.core.nftables

import com.materialxray.core.root.RootShell

class NftablesManager(private val shell: RootShell) {

    suspend fun apply(fwmark: Int, bypassUids: Set<Int>) {
        val uidElements = if (bypassUids.isNotEmpty()) {
            "elements = { ${bypassUids.joinToString(", ")} }"
        } else ""

        val ruleset = buildString {
            appendLine("table inet xray {")
            appendLine("    set bypass_uids {")
            appendLine("        type uid_t")
            if (uidElements.isNotEmpty()) appendLine("        $uidElements")
            appendLine("    }")
            appendLine()
            appendLine("    chain output {")
            appendLine("        type route hook output priority 0; policy accept;")
            appendLine("        meta mark $fwmark accept")
            appendLine("        oifname \"lo\" accept")
            if (bypassUids.isNotEmpty()) {
                appendLine("        meta skuid @bypass_uids accept")
            }
            appendLine("        ip protocol icmp accept")
            appendLine("        ip6 nexthdr icmpv6 accept")
            appendLine("        meta mark set ${fwmark - 155}") // 100 by default (255-155)
            appendLine("    }")
            appendLine("}")
        }

        // Atomic: delete old table if exists, then load new ruleset
        shell.execute("nft delete table inet xray 2>/dev/null; echo '${ruleset.replace("'", "'\\''")}' | nft -f -")
    }

    suspend fun remove() {
        shell.execute("nft delete table inet xray 2>/dev/null")
    }

    suspend fun exists(): Boolean {
        val result = shell.execute("nft list tables 2>/dev/null")
        return result.output.contains("inet xray")
    }
}
```

- [ ] **Step 2: Create StateFile**

Create `app/src/main/kotlin/com/materialxray/core/xray/StateFile.kt`:

```kotlin
package com.materialxray.core.xray

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class XrayState(
    val xrayPid: Int = -1,
    val tunName: String = "xray0",
    val nftTableCreated: Boolean = false,
    val ipRulesApplied: Boolean = false,
    val routeTable: Int = 100,
    val fwmark: Int = 255,
    val timestamp: Long = System.currentTimeMillis(),
)

class StateFile(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun read(): XrayState? = runCatching {
        if (!file.exists()) return null
        json.decodeFromString<XrayState>(file.readText())
    }.getOrNull()

    fun write(state: XrayState) {
        file.writeText(json.encodeToString(state))
    }

    fun delete() {
        file.delete()
    }
}
```

- [ ] **Step 3: Create TunManager**

Create `app/src/main/kotlin/com/materialxray/core/xray/TunManager.kt`:

```kotlin
package com.materialxray.core.xray

import com.materialxray.core.root.RootShell
import kotlinx.coroutines.delay

class TunManager(private val shell: RootShell) {

    suspend fun configureTun(tunName: String): Boolean {
        // Wait for TUN interface to appear (xray creates it)
        var attempts = 0
        while (attempts < 30) {
            val result = shell.execute("ip link show $tunName 2>/dev/null")
            if (result.isSuccess && result.output.contains(tunName)) break
            delay(200)
            attempts++
        }
        if (attempts >= 30) return false

        // Configure address and bring up
        shell.execute("ip addr add 10.0.0.1/30 dev $tunName 2>/dev/null")
        val upResult = shell.execute("ip link set $tunName up")
        return upResult.isSuccess
    }

    suspend fun applyRouting(tunName: String, fwmark: Int, routeTable: Int): Boolean {
        val routeMark = fwmark - 155 // maps 255 -> 100

        // Add ip rules
        shell.execute("ip rule add fwmark $fwmark table main prio 10")
        shell.execute("ip rule add fwmark $routeMark table $routeTable prio 20")

        // Add default route through TUN in our table
        val routeResult = shell.execute("ip route add default dev $tunName table $routeTable")
        return routeResult.isSuccess
    }

    suspend fun removeRouting(tunName: String, fwmark: Int, routeTable: Int) {
        val routeMark = fwmark - 155
        shell.execute("ip rule del fwmark $fwmark table main prio 10 2>/dev/null")
        shell.execute("ip rule del fwmark $routeMark table $routeTable prio 20 2>/dev/null")
        shell.execute("ip route flush table $routeTable 2>/dev/null")
        shell.execute("ip link del $tunName 2>/dev/null")
    }
}
```

- [ ] **Step 4: Create CleanupManager**

Create `app/src/main/kotlin/com/materialxray/core/xray/CleanupManager.kt`:

```kotlin
package com.materialxray.core.xray

import android.content.Context
import com.materialxray.core.nftables.NftablesManager
import com.materialxray.core.root.RootShell

class CleanupManager(
    context: Context,
    private val shell: RootShell,
) {
    private val stateFile = StateFile(context)
    private val nftables = NftablesManager(shell)
    private val tunManager = TunManager(shell)

    suspend fun ensureCleanState() {
        val state = stateFile.read()

        // 1. Kill orphaned xray process
        if (state != null && state.xrayPid > 0) {
            shell.execute("kill ${state.xrayPid} 2>/dev/null")
        }
        shell.execute("pkill -f 'xray run' 2>/dev/null")

        // 2. Remove nftables table (atomic — removes everything under it)
        nftables.remove()

        // 3. Remove ip rules and routes
        val tunName = state?.tunName ?: "xray0"
        val fwmark = state?.fwmark ?: 255
        val routeTable = state?.routeTable ?: 100
        tunManager.removeRouting(tunName, fwmark, routeTable)

        // 4. Delete state file
        stateFile.delete()
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/core/
git commit -m "feat: add NftablesManager, TunManager, StateFile, CleanupManager"
```

---

### Task 10: ConnectionManager & XrayBinary

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/core/xray/XrayBinary.kt`
- Create: `app/src/main/kotlin/com/materialxray/service/ConnectionManager.kt`

- [ ] **Step 1: Create XrayBinary**

Create `app/src/main/kotlin/com/materialxray/core/xray/XrayBinary.kt`:

```kotlin
package com.materialxray.core.xray

import android.content.Context
import com.materialxray.core.root.RootShell
import java.io.File

class XrayBinary(private val context: Context) {

    private val binaryDir = File(context.filesDir, "bin")
    val binaryPath: String get() = File(binaryDir, "xray").absolutePath

    fun ensureExtracted(): Boolean {
        binaryDir.mkdirs()
        val target = File(binaryDir, "xray")

        // Check if binary from native libs exists (bundled in jniLibs as libxray.so)
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!nativeLib.exists()) return false

        // Copy if missing or different size
        if (!target.exists() || target.length() != nativeLib.length()) {
            nativeLib.copyTo(target, overwrite = true)
            target.setExecutable(true)
        }
        return target.exists() && target.canExecute()
    }

    fun configPath(): String = File(context.filesDir, "config.json").absolutePath

    fun writeConfig(configJson: String) {
        File(context.filesDir, "config.json").writeText(configJson)
    }
}
```

- [ ] **Step 2: Create ConnectionManager**

Create `app/src/main/kotlin/com/materialxray/service/ConnectionManager.kt`:

```kotlin
package com.materialxray.service

import android.content.Context
import com.materialxray.core.nftables.NftablesManager
import com.materialxray.core.root.RootShell
import com.materialxray.core.xray.*
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.model.ConnectionState
import com.materialxray.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConnectionManager(
    private val context: Context,
    private val shell: RootShell,
    private val configGenerator: ConfigGenerator,
    private val appBypassDao: AppBypassDao,
) {
    private val xrayBinary = XrayBinary(context)
    private val tunManager = TunManager(shell)
    private val nftablesManager = NftablesManager(shell)
    private val cleanupManager = CleanupManager(context, shell)
    private val stateFile = StateFile(context)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    suspend fun connect(server: ServerConfig, tunName: String, fwmark: Int, routeTable: Int, dnsServers: String) {
        _state.value = ConnectionState.Connecting

        try {
            // 1. Clean any stale state
            cleanupManager.ensureCleanState()

            // 2. Ensure root access
            if (!shell.open()) {
                _state.value = ConnectionState.Error("Root access denied")
                return
            }

            // 3. Ensure xray binary
            if (!xrayBinary.ensureExtracted()) {
                _state.value = ConnectionState.Error("xray binary not found")
                return
            }

            // 4. Generate and write config
            val configJson = configGenerator.generate(server, tunName, fwmark, dnsServers)
            xrayBinary.writeConfig(configJson)

            // 5. Start xray
            val startResult = shell.execute("${xrayBinary.binaryPath} run -c ${xrayBinary.configPath()} &")
            if (!startResult.isSuccess && startResult.error.isNotEmpty()) {
                _state.value = ConnectionState.Error("Failed to start xray: ${startResult.error}")
                return
            }

            // Get PID
            val pidResult = shell.execute("pgrep -f 'xray run'")
            val pid = pidResult.output.trim().lines().firstOrNull()?.toIntOrNull() ?: -1

            // 6. Write initial state
            stateFile.write(XrayState(
                xrayPid = pid,
                tunName = tunName,
                fwmark = fwmark,
                routeTable = routeTable,
            ))

            // 7. Configure TUN
            if (!tunManager.configureTun(tunName)) {
                cleanupManager.ensureCleanState()
                _state.value = ConnectionState.Error("TUN interface $tunName did not come up")
                return
            }

            // 8. Apply nftables rules
            val bypassUids = appBypassDao.getExcluded().map { it.uid }.toSet()
            nftablesManager.apply(fwmark, bypassUids)

            // 9. Apply ip routing
            tunManager.applyRouting(tunName, fwmark, routeTable)

            // 10. Update state file
            stateFile.write(XrayState(
                xrayPid = pid,
                tunName = tunName,
                nftTableCreated = true,
                ipRulesApplied = true,
                fwmark = fwmark,
                routeTable = routeTable,
            ))

            _state.value = ConnectionState.Connected(serverName = server.name)

        } catch (e: Exception) {
            cleanupManager.ensureCleanState()
            _state.value = ConnectionState.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun disconnect() {
        _state.value = ConnectionState.Disconnecting
        cleanupManager.ensureCleanState()
        _state.value = ConnectionState.Disconnected
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/core/xray/XrayBinary.kt app/src/main/kotlin/com/materialxray/service/ConnectionManager.kt
git commit -m "feat: add XrayBinary extraction and ConnectionManager orchestration"
```

---

### Task 11: Foreground Service & Boot Receiver

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/service/XrayService.kt`
- Create: `app/src/main/kotlin/com/materialxray/service/BootReceiver.kt`

- [ ] **Step 1: Create XrayService**

Create `app/src/main/kotlin/com/materialxray/service/XrayService.kt`:

```kotlin
package com.materialxray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.materialxray.MainActivity
import com.materialxray.R
import com.materialxray.core.root.RootShell
import com.materialxray.core.xray.ConfigGenerator
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.model.ConnectionState
import com.materialxray.model.ServerConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class XrayService : Service() {

    @Inject lateinit var rootShell: RootShell
    @Inject lateinit var appBypassDao: AppBypassDao
    @Inject lateinit var settingsRepo: SettingsRepository

    private lateinit var connectionManager: ConnectionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val connectionState: StateFlow<ConnectionState> get() = connectionManager.state

    override fun onCreate() {
        super.onCreate()
        connectionManager = ConnectionManager(
            context = this,
            shell = rootShell,
            configGenerator = ConfigGenerator(),
            appBypassDao = appBypassDao,
        )
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Disconnected"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_SERVER_CONFIG) ?: return START_NOT_STICKY
                scope.launch {
                    val config = Json.decodeFromString<ServerConfig>(configJson)
                    val tunName = settingsRepo.tunName.first()
                    val fwmark = settingsRepo.fwmark.first()
                    val routeTable = settingsRepo.routeTable.first()
                    val dns = settingsRepo.dnsServers.first()
                    connectionManager.connect(config, tunName, fwmark, routeTable, dns)
                    updateNotification()
                }
            }
            ACTION_DISCONNECT -> {
                scope.launch {
                    connectionManager.disconnect()
                    updateNotification()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch {
            connectionManager.disconnect()
        }
        scope.cancel()
        rootShell.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        val text = when (val s = connectionManager.state.value) {
            is ConnectionState.Connected -> "Connected to ${s.serverName}"
            is ConnectionState.Connecting -> "Connecting..."
            is ConnectionState.Disconnecting -> "Disconnecting..."
            is ConnectionState.Error -> "Error: ${s.message}"
            ConnectionState.Disconnected -> "Disconnected"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, XrayService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MaterialXray")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Xray Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "xray_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.materialxray.CONNECT"
        const val ACTION_DISCONNECT = "com.materialxray.DISCONNECT"
        const val EXTRA_SERVER_CONFIG = "server_config"

        fun connect(context: Context, serverConfig: ServerConfig) {
            val intent = Intent(context, XrayService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_CONFIG, Json.encodeToString(ServerConfig.serializer(), serverConfig))
            }
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, XrayService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
```

- [ ] **Step 2: Create BootReceiver**

Create `app/src/main/kotlin/com/materialxray/service/BootReceiver.kt`:

```kotlin
package com.materialxray.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.materialxray.core.root.RootShell
import com.materialxray.core.xray.CleanupManager
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.materialxray.model.ServerConfig
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var serverDao: ServerDao
    @Inject lateinit var rootShell: RootShell

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        CoroutineScope(Dispatchers.IO).launch {
            // Clean up any stale state from before reboot
            CleanupManager(context, rootShell).ensureCleanState()

            val autoConnect = settingsRepo.autoConnect.first()
            if (!autoConnect) return@launch

            val lastServerId = settingsRepo.lastServerId.first()
            if (lastServerId < 0) return@launch

            val serverEntity = serverDao.getById(lastServerId) ?: return@launch
            val config = Json.decodeFromString<ServerConfig>(serverEntity.configJson)
            XrayService.connect(context, config)
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/service/
git commit -m "feat: add XrayService foreground service and BootReceiver"
```

---

### Task 12: MD3 Theme & Navigation Shell

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/com/materialxray/ui/navigation/Screen.kt`
- Create: `app/src/main/kotlin/com/materialxray/ui/navigation/MainNavigation.kt`

- [ ] **Step 1: Create Theme**

Create `app/src/main/kotlin/com/materialxray/ui/theme/Theme.kt`:

```kotlin
package com.materialxray.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MaterialXrayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
```

- [ ] **Step 2: Create Navigation**

Create `app/src/main/kotlin/com/materialxray/ui/navigation/Screen.kt`:

```kotlin
package com.materialxray.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Default.Home),
    Servers("servers", "Servers", Icons.Default.Storage),
    Apps("apps", "Apps", Icons.Default.Apps),
    Settings("settings", "Settings", Icons.Default.Settings),
}
```

Create `app/src/main/kotlin/com/materialxray/ui/navigation/MainNavigation.kt`:

```kotlin
package com.materialxray.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.materialxray.ui.apps.AppsScreen
import com.materialxray.ui.home.HomeScreen
import com.materialxray.ui.servers.ServersScreen
import com.materialxray.ui.settings.SettingsScreen

@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Servers.route) { ServersScreen() }
            composable(Screen.Apps.route) { AppsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/ui/theme/ app/src/main/kotlin/com/materialxray/ui/navigation/
git commit -m "feat: add MD3 dynamic theme and bottom navigation shell"
```

---

### Task 13: Home Screen

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/ui/home/HomeViewModel.kt`
- Create: `app/src/main/kotlin/com/materialxray/ui/home/HomeScreen.kt`

- [ ] **Step 1: Create HomeViewModel**

Create `app/src/main/kotlin/com/materialxray/ui/home/HomeViewModel.kt`:

```kotlin
package com.materialxray.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.model.ConnectionState
import com.materialxray.model.ServerConfig
import com.materialxray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    // Connection state — observed from service via broadcast or shared flow
    // For simplicity, use a local state that mirrors the service
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    val lastServerId: StateFlow<Long> = settingsRepo.lastServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    private val _selectedServer = MutableStateFlow<ServerConfig?>(null)
    val selectedServer: StateFlow<ServerConfig?> = _selectedServer

    init {
        viewModelScope.launch {
            settingsRepo.lastServerId.collect { id ->
                if (id >= 0) {
                    val entity = serverDao.getById(id)
                    _selectedServer.value = entity?.let {
                        runCatching { json.decodeFromString<ServerConfig>(it.configJson) }.getOrNull()
                    }
                }
            }
        }
    }

    fun connect() {
        val server = _selectedServer.value ?: return
        _connectionState.value = ConnectionState.Connecting
        XrayService.connect(context, server)
        // The service will update state — in production, use a shared state mechanism
        _connectionState.value = ConnectionState.Connected(serverName = server.name)
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.Disconnecting
        XrayService.disconnect(context)
        _connectionState.value = ConnectionState.Disconnected
    }

    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            settingsRepo.setLastServerId(serverId)
        }
    }
}
```

- [ ] **Step 2: Create HomeScreen**

Create `app/src/main/kotlin/com/materialxray/ui/home/HomeScreen.kt`:

```kotlin
package com.materialxray.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.materialxray.model.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val connectionState by viewModel.connectionState.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected
    val isTransitioning = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Disconnecting

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> MaterialTheme.colorScheme.error
            isTransitioning -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor",
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MaterialXray") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Status text
            Text(
                text = when (val state = connectionState) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Connecting -> "Connecting..."
                    is ConnectionState.Disconnecting -> "Disconnecting..."
                    is ConnectionState.Error -> "Error"
                    ConnectionState.Disconnected -> "Disconnected"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = when {
                    isConnected -> MaterialTheme.colorScheme.primary
                    connectionState is ConnectionState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Server name
            Text(
                text = selectedServer?.name ?: "No server selected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Connect button
            FilledTonalButton(
                onClick = {
                    if (isConnected) viewModel.disconnect()
                    else if (!isTransitioning) viewModel.connect()
                },
                enabled = !isTransitioning && selectedServer != null,
                modifier = Modifier.size(140.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = buttonColor.copy(alpha = 0.15f),
                    contentColor = buttonColor,
                ),
            ) {
                Text(
                    text = if (isConnected) "Stop" else "Start",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Error message
            if (connectionState is ConnectionState.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (connectionState as ConnectionState.Error).message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/ui/home/
git commit -m "feat: add Home screen with connection toggle and status display"
```

---

### Task 14: Servers Screen

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/ui/servers/ServersViewModel.kt`
- Create: `app/src/main/kotlin/com/materialxray/ui/servers/ServersScreen.kt`

- [ ] **Step 1: Create ServersViewModel**

Create `app/src/main/kotlin/com/materialxray/ui/servers/ServersViewModel.kt`:

```kotlin
package com.materialxray.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity
import com.materialxray.data.repository.ServerRepository
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.data.repository.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val subscriptionRepo: SubscriptionRepository,
    private val serverRepo: ServerRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val servers: StateFlow<List<ServerEntity>> = serverRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServerId: StateFlow<Long> = settingsRepo.lastServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            runCatching { subscriptionRepo.add(name, url) }
        }
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            subscriptionRepo.delete(sub)
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { subscriptionRepo.refreshAll() }
            _isRefreshing.value = false
        }
    }

    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            settingsRepo.setLastServerId(serverId)
        }
    }

    fun testLatency(server: ServerEntity) {
        viewModelScope.launch {
            val latency = withContext(Dispatchers.IO) {
                measureLatency(server.address, server.port)
            }
            serverRepo.updateLatency(server.id, latency)
        }
    }

    fun testAllLatencies() {
        viewModelScope.launch {
            servers.value.forEach { server ->
                launch {
                    val latency = withContext(Dispatchers.IO) {
                        measureLatency(server.address, server.port)
                    }
                    serverRepo.updateLatency(server.id, latency)
                }
            }
        }
    }

    private fun measureLatency(address: String, port: Int): Int = runCatching {
        val start = System.currentTimeMillis()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), 3000)
        }
        (System.currentTimeMillis() - start).toInt()
    }.getOrElse { -1 }
}
```

- [ ] **Step 2: Create ServersScreen**

Create `app/src/main/kotlin/com/materialxray/ui/servers/ServersScreen.kt`:

```kotlin
package com.materialxray.ui.servers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.materialxray.data.db.entity.ServerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(viewModel: ServersViewModel = hiltViewModel()) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = {
                    IconButton(onClick = { viewModel.testAllLatencies() }) {
                        Icon(Icons.Default.Speed, contentDescription = "Test all")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add subscription")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                subscriptions.forEach { sub ->
                    item(key = "header_${sub.id}") {
                        SubscriptionHeader(
                            name = sub.name,
                            url = sub.url,
                            onDelete = { viewModel.deleteSubscription(sub) },
                        )
                    }
                    val subServers = servers.filter { it.subscriptionId == sub.id }
                    items(subServers, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            isSelected = server.id == selectedServerId,
                            onClick = { viewModel.selectServer(server.id) },
                            onTestLatency = { viewModel.testLatency(server) },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                viewModel.addSubscription(name, url)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun SubscriptionHeader(name: String, url: String, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(name, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Text("...", style = MaterialTheme.typography.bodyLarge)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: ServerEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(server.name) },
        supportingContent = {
            Text("${server.protocol} • ${server.address}:${server.port}")
        },
        trailingContent = {
            val latencyText = when {
                server.latencyMs < 0 -> "—"
                else -> "${server.latencyMs}ms"
            }
            val color = when {
                server.latencyMs < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                server.latencyMs < 200 -> MaterialTheme.colorScheme.primary
                server.latencyMs < 500 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            Text(latencyText, color = color, style = MaterialTheme.typography.bodyMedium)
        },
        leadingContent = {
            RadioButton(selected = isSelected, onClick = onClick)
        },
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onTestLatency,
        ),
    )
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Subscription") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/ui/servers/
git commit -m "feat: add Servers screen with subscription management, latency testing"
```

---

### Task 15: Apps Screen

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/ui/apps/AppsViewModel.kt`
- Create: `app/src/main/kotlin/com/materialxray/ui/apps/AppsScreen.kt`

- [ ] **Step 1: Create AppsViewModel**

Create `app/src/main/kotlin/com/materialxray/ui/apps/AppsViewModel.kt`:

```kotlin
package com.materialxray.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.entity.AppBypassEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val isExcluded: Boolean,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appBypassDao: AppBypassDao,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val bypassedApps: StateFlow<List<AppBypassEntity>> = appBypassDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())

    val apps: StateFlow<List<AppItem>> = combine(
        _installedApps, bypassedApps, _searchQuery,
    ) { installed, bypassed, query ->
        val bypassSet = bypassed.filter { it.excluded }.map { it.packageName }.toSet()
        installed
            .map { it.copy(isExcluded = it.packageName in bypassSet) }
            .filter {
                query.isEmpty() ||
                it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { info ->
                        AppItem(
                            packageName = info.packageName,
                            name = info.loadLabel(pm).toString(),
                            uid = info.uid,
                            icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                            isExcluded = false,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
            _installedApps.value = apps
        }
    }

    fun toggleExclude(app: AppItem) {
        viewModelScope.launch {
            if (app.isExcluded) {
                appBypassDao.delete(app.packageName)
            } else {
                appBypassDao.upsert(AppBypassEntity(
                    packageName = app.packageName,
                    uid = app.uid,
                    excluded = true,
                ))
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun excludeAll() {
        viewModelScope.launch {
            _installedApps.value.forEach { app ->
                appBypassDao.upsert(AppBypassEntity(app.packageName, app.uid, excluded = true))
            }
        }
    }

    fun includeAll() {
        viewModelScope.launch {
            appBypassDao.deleteAll()
        }
    }
}
```

- [ ] **Step 2: Create AppsScreen**

Create `app/src/main/kotlin/com/materialxray/ui/apps/AppsScreen.kt`:

```kotlin
package com.materialxray.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(viewModel: AppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Bypass") },
                actions = {
                    IconButton(onClick = { viewModel.excludeAll() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Exclude all")
                    }
                    IconButton(onClick = { viewModel.includeAll() }) {
                        Icon(Icons.Default.Deselect, contentDescription = "Include all")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.name) },
                        supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                        leadingContent = {
                            if (app.icon != null) {
                                Image(
                                    painter = rememberDrawablePainter(app.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = app.isExcluded,
                                onCheckedChange = { viewModel.toggleExclude(app) },
                            )
                        },
                    )
                }
            }
        }
    }
}
```

**Note:** The `rememberDrawablePainter` function is from the Accompanist library. Add this dependency to `app/build.gradle.kts`:

```kotlin
implementation("com.google.accompanist:accompanist-drawablepainter:0.36.0")
```

Wait — Accompanist's drawable painter may not be needed if we use `coil` or paint the drawable manually. Let's avoid the extra dependency and use a simpler approach. Replace the `Image` usage with:

```kotlin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

// In the composable:
leadingContent = {
    app.icon?.let { drawable ->
        Image(
            bitmap = drawable.toBitmap(40, 40).asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    }
},
```

This uses only core Android + Compose APIs. No Accompanist needed.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/ui/apps/
git commit -m "feat: add Apps screen with per-app bypass toggles and search"
```

---

### Task 16: Settings Screen with Backup/Restore

**Files:**
- Create: `app/src/main/kotlin/com/materialxray/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/kotlin/com/materialxray/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Create SettingsViewModel**

Create `app/src/main/kotlin/com/materialxray/ui/settings/SettingsViewModel.kt`:

```kotlin
package com.materialxray.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.model.BackupData
import com.materialxray.model.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val appBypassDao: AppBypassDao,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val tunName = settingsRepo.tunName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "xray0")
    val dnsServers = settingsRepo.dnsServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.1.1.1,8.8.8.8")
    val autoConnect = settingsRepo.autoConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setTunName(name: String) = viewModelScope.launch { settingsRepo.setTunName(name) }
    fun setDnsServers(servers: String) = viewModelScope.launch { settingsRepo.setDnsServers(servers) }
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(enabled) }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val subs = subscriptionDao.getAll()
            val allServers = mutableListOf<BackupData.BackupServer>()
            subs.forEach { sub ->
                // Collect servers per subscription by reading all from DB
            }
            val bypassed = appBypassDao.getExcluded().map { it.packageName }
            val settings = settingsRepo.getAllAsMap()

            val backup = BackupData(
                subscriptions = subs.map { BackupData.BackupSubscription(it.name, it.url) },
                servers = emptyList(), // Servers regenerate from subscriptions on restore
                bypassedApps = bypassed,
                settings = settings,
            )

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.encodeToString(backup).toByteArray())
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: return@launch
            val backup = runCatching { json.decodeFromString<BackupData>(text) }.getOrNull() ?: return@launch

            // Clear existing data
            subscriptionDao.deleteAll()
            serverDao.deleteAll()
            appBypassDao.deleteAll()

            // Restore subscriptions (servers will be fetched on refresh)
            backup.subscriptions.forEach { sub ->
                subscriptionDao.insert(
                    com.materialxray.data.db.entity.SubscriptionEntity(name = sub.name, url = sub.url)
                )
            }

            // Restore bypass apps
            backup.bypassedApps.forEach { pkg ->
                // UID will be resolved when the Apps screen loads
                appBypassDao.upsert(
                    com.materialxray.data.db.entity.AppBypassEntity(packageName = pkg, uid = 0, excluded = true)
                )
            }

            // Restore settings
            settingsRepo.restoreFromMap(backup.settings)
        }
    }
}
```

- [ ] **Step 2: Create SettingsScreen**

Create `app/src/main/kotlin/com/materialxray/ui/settings/SettingsScreen.kt`:

```kotlin
package com.materialxray.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val tunName by viewModel.tunName.collectAsState()
    val dnsServers by viewModel.dnsServers.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()

    var editingTunName by remember(tunName) { mutableStateOf(tunName) }
    var editingDns by remember(dnsServers) { mutableStateOf(dnsServers) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // TUN Interface
            Text("Network", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = editingTunName,
                onValueChange = { editingTunName = it },
                label = { Text("TUN Interface Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Default: xray0") },
            )
            if (editingTunName != tunName) {
                TextButton(onClick = { viewModel.setTunName(editingTunName) }) {
                    Text("Save")
                }
            }

            OutlinedTextField(
                value = editingDns,
                onValueChange = { editingDns = it },
                label = { Text("DNS Servers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Comma-separated, e.g. 1.1.1.1,8.8.8.8") },
            )
            if (editingDns != dnsServers) {
                TextButton(onClick = { viewModel.setDnsServers(editingDns) }) {
                    Text("Save")
                }
            }

            HorizontalDivider()

            // Auto-connect
            Text("Startup", style = MaterialTheme.typography.titleMedium)

            ListItem(
                headlineContent = { Text("Auto-connect on boot") },
                supportingContent = { Text("Reconnect to last server after device restart") },
                trailingContent = {
                    Switch(checked = autoConnect, onCheckedChange = { viewModel.setAutoConnect(it) })
                },
            )

            HorizontalDivider()

            // Backup/Restore
            Text("Data", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("materialxray-backup.json") }) {
                    Text("Export Backup")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text("Import Backup")
                }
            }

            HorizontalDivider()

            // About
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("MaterialXray v1.0.0", style = MaterialTheme.typography.bodyMedium)
            Text("xray-core v26.3.27", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/materialxray/ui/settings/
git commit -m "feat: add Settings screen with TUN config, DNS, auto-connect, backup/restore"
```

---

### Task 17: Download xray-core Binary

**Files:**
- Create: `app/src/main/jniLibs/arm64-v8a/libxray.so` (binary, downloaded)
- Create: `scripts/download-xray.sh`

- [ ] **Step 1: Create download script**

Create `scripts/download-xray.sh`:

```bash
#!/bin/bash
set -euo pipefail

VERSION="v26.3.27"
BASE_URL="https://github.com/XTLS/Xray-core/releases/download/${VERSION}"

mkdir -p app/src/main/jniLibs/arm64-v8a
mkdir -p app/src/main/jniLibs/armeabi-v7a
mkdir -p app/src/main/jniLibs/x86_64

echo "Downloading xray-core ${VERSION}..."

# arm64
curl -sL "${BASE_URL}/Xray-android-arm64-v8a.zip" -o /tmp/xray-arm64.zip
unzip -o /tmp/xray-arm64.zip xray -d /tmp/xray-arm64
mv /tmp/xray-arm64/xray app/src/main/jniLibs/arm64-v8a/libxray.so
rm -rf /tmp/xray-arm64 /tmp/xray-arm64.zip

# arm32
curl -sL "${BASE_URL}/Xray-android-arm32-v7a.zip" -o /tmp/xray-arm32.zip
unzip -o /tmp/xray-arm32.zip xray -d /tmp/xray-arm32
mv /tmp/xray-arm32/xray app/src/main/jniLibs/armeabi-v7a/libxray.so
rm -rf /tmp/xray-arm32 /tmp/xray-arm32.zip

# x86_64
curl -sL "${BASE_URL}/Xray-android-x86_64.zip" -o /tmp/xray-x86_64.zip
unzip -o /tmp/xray-x86_64.zip xray -d /tmp/xray-x86_64
mv /tmp/xray-x86_64/xray app/src/main/jniLibs/x86_64/libxray.so
rm -rf /tmp/xray-x86_64 /tmp/xray-x86_64.zip

echo "Done. Binaries placed in app/src/main/jniLibs/"
ls -lh app/src/main/jniLibs/*/libxray.so
```

- [ ] **Step 2: Run download script**

Run: `bash scripts/download-xray.sh`
Expected: Three `libxray.so` files in `jniLibs/` directories.

- [ ] **Step 3: Add .gitignore for binaries, commit script**

Create `.gitignore` in project root (or append):

```
# Large binaries
app/src/main/jniLibs/

# Build
build/
.gradle/
*.iml
.idea/
local.properties
```

```bash
git add scripts/download-xray.sh .gitignore
git commit -m "feat: add xray-core binary download script for arm64, arm32, x86_64"
```

---

### Task 18: Build Verification & Integration

- [ ] **Step 1: Generate Gradle wrapper if not done**

Run: `cd /home/aether/Projects/MaterialXray && gradle wrapper --gradle-version 9.1`

- [ ] **Step 2: Run build**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. Fix any compilation errors.

- [ ] **Step 3: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: All parser and config generator tests pass.

- [ ] **Step 4: Commit any build fixes**

```bash
git add -A
git commit -m "fix: resolve build issues from integration"
```

- [ ] **Step 5: Final commit — tag v0.1.0**

```bash
git tag v0.1.0
```
