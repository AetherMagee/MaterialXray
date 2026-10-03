package com.material.xray.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseValueValidatorTest {
    @Test
    fun validationRunsOnlyForMissingOrOlderRevisions() {
        assertTrue(DatabaseValueValidator.shouldValidate(null))
        assertTrue(DatabaseValueValidator.shouldValidate(DatabaseValueValidator.CURRENT_REVISION - 1))
        assertFalse(DatabaseValueValidator.shouldValidate(DatabaseValueValidator.CURRENT_REVISION))
        assertFalse(DatabaseValueValidator.shouldValidate(DatabaseValueValidator.CURRENT_REVISION + 1))
    }

    @Test
    fun validationSanitizesRowsWithoutDeletingThemAndIsIdempotent() {
        openInMemory().use { connection ->
            connection.createSchema()
            connection.seedInvalidRows()

            assertTrue(connection.executeValidation() > 0)
            connection.assertSanitizedRows()
            assertEquals(0, connection.executeValidation())
        }
    }

    private fun SQLiteConnection.createSchema() {
        execSQL(
            """
            CREATE TABLE subscriptions (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                preferJson INTEGER,
                lastUpdated INTEGER NOT NULL,
                contentDisposition TEXT,
                contentType TEXT,
                profileTitle TEXT,
                profileUpdateIntervalHours INTEGER,
                autoUpdateIntervalHours INTEGER NOT NULL,
                subscriptionUploadBytes INTEGER,
                subscriptionDownloadBytes INTEGER,
                subscriptionTotalBytes INTEGER,
                subscriptionExpireAt INTEGER,
                profileWebPageUrl TEXT,
                announce TEXT,
                supportUrl TEXT,
                fallbackUrl TEXT,
                requiresHardwareId INTEGER NOT NULL DEFAULT 0,
                descriptionHidden INTEGER NOT NULL,
                userAgentMode TEXT,
                customUserAgent TEXT,
                customHeaders TEXT,
                appRoutingPackages TEXT,
                appRoutingMode TEXT,
                appRoutingInverted INTEGER NOT NULL DEFAULT 0,
                providerRouting TEXT
            )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE TABLE servers (
                id INTEGER PRIMARY KEY,
                subscriptionId INTEGER NOT NULL,
                name TEXT NOT NULL,
                protocol TEXT NOT NULL,
                address TEXT NOT NULL,
                port INTEGER NOT NULL,
                configJson TEXT NOT NULL,
                latencyMs INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE TABLE app_bypass (
                packageName TEXT NOT NULL,
                profileId INTEGER NOT NULL,
                uid INTEGER NOT NULL,
                excluded INTEGER NOT NULL,
                serverId INTEGER,
                manual INTEGER NOT NULL,
                routeMode TEXT,
                alwaysProxied INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(profileId, packageName)
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.seedInvalidRows() {
        execSQL(
            """
            INSERT INTO subscriptions (
                id, name, url, preferJson, lastUpdated, autoUpdateIntervalHours,
                subscriptionTotalBytes, subscriptionExpireAt, descriptionHidden,
                userAgentMode, appRoutingPackages, appRoutingMode, appRoutingInverted,
                fallbackUrl, requiresHardwareId
            ) VALUES (
                1, '  ', ' https://example.com ', 2, -1, -6,
                -1, 0, 4, ' CUSTOM ', ' ["com.example"] ', ' bypass ', 7,
                '  https://backup.example.com  ', 9
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO servers (
                id, subscriptionId, name, protocol, address, port,
                configJson, latencyMs, sortOrder
            ) VALUES (
                10, 1, ' ', 'vless', ' example.com ', 70000,
                '{}', -5, -1
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO app_bypass (
                packageName, profileId, uid, excluded, serverId, manual, routeMode
            ) VALUES (' ', -1, 1234, 1, 10, 1, ' SERVER ')
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO app_bypass (
                packageName, profileId, uid, excluded, serverId, manual, routeMode
            ) VALUES ('com.example.app', 0, 1234, 1, NULL, 1, 'bypass')
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.executeValidation(): Int = DatabaseValueValidator.statements.sumOf { sql ->
        execSQL(sql)
        changes()
    }

    private fun SQLiteConnection.assertSanitizedRows() {
        val subscription = query("SELECT * FROM subscriptions").single()
        assertEquals("Subscription 1", subscription["name"])
        assertEquals("https://example.com", subscription["url"])
        assertNull(subscription["preferJson"])
        assertEquals("0", subscription["lastUpdated"])
        assertEquals("0", subscription["autoUpdateIntervalHours"])
        assertNull(subscription["subscriptionTotalBytes"])
        assertNull(subscription["subscriptionExpireAt"])
        assertEquals("1", subscription["descriptionHidden"])
        assertEquals("custom", subscription["userAgentMode"])
        assertEquals("[\"com.example\"]", subscription["appRoutingPackages"])
        assertEquals("direct", subscription["appRoutingMode"])
        assertEquals("1", subscription["appRoutingInverted"])
        assertEquals("https://backup.example.com", subscription["fallbackUrl"])
        assertEquals("1", subscription["requiresHardwareId"])

        val server = query("SELECT * FROM servers").single()
        assertEquals("Server 10", server["name"])
        assertEquals("VLESS", server["protocol"])
        assertEquals("example.com", server["address"])
        assertEquals("0", server["port"])
        assertEquals("-1", server["latencyMs"])
        assertEquals("0", server["sortOrder"])

        val (invalid, bypassed) = query("SELECT * FROM app_bypass ORDER BY profileId")
        assertEquals("0", invalid["uid"])
        assertEquals("0", invalid["excluded"])
        assertNull(invalid["serverId"])
        assertEquals("0", invalid["manual"])
        assertNull(invalid["routeMode"])
        assertEquals("bypass", bypassed["routeMode"])
    }
}
