package com.material.xray.core.database

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.material.xray.core.common.log.NoOpAppLogger
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Replays the whole migration chain on a bundled SQLite database and compares the result against the
 * schema Room exports for the current version.
 *
 * Room only detects a broken migration at runtime, on a user's device, by comparing identity
 * hashes. This test moves that detection into the build.
 */
class DatabaseMigrationChainTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun migrationsCoverEveryVersionUpToTheCurrentOne() {
        val schema = readCurrentSchema()
        val startVersions = DatabaseMigrations.sqlByStartVersion.keys.sorted()

        assertEquals(
            "Migrations must form a contiguous chain from version 1 to ${schema.database.version}",
            (1 until schema.database.version).toList(),
            startVersions,
        )
    }

    @Test
    fun migratedSchemaMatchesFreshlyCreatedSchema() {
        val schema = readCurrentSchema()

        openInMemory().use { migrated ->
            openInMemory().use { fresh ->
                migrated.applyVersionOneSchema()
                DatabaseMigrations.sqlByStartVersion.entries
                    .sortedBy { it.key }
                    .forEach { (_, statements) -> statements.forEach { migrated.runSql(it) } }

                schema.database.entities.forEach { entity ->
                    fresh.runSql(entity.createSql.forTable(entity.tableName))
                    entity.indices.forEach { index -> fresh.runSql(index.createSql.forTable(entity.tableName)) }
                }

                assertEquals(fresh.tableNames(), migrated.tableNames())
                fresh.tableNames().forEach { table ->
                    assertColumnsMatch(table, expected = fresh.columns(table), actual = migrated.columns(table))
                    assertEquals("Indices of $table", fresh.indices(table), migrated.indices(table))
                    assertEquals("Foreign keys of $table", fresh.foreignKeys(table), migrated.foreignKeys(table))
                }
            }
        }
    }

    @Test
    fun subscriptionOrderMigrationKeepsExistingRowsAheadOfNewRows() {
        openInMemory().use { connection ->
            connection.runSql("CREATE TABLE subscriptions (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            connection.runSql("INSERT INTO subscriptions VALUES (4, 'First'), (9, 'Second')")
            DatabaseMigrations.sqlByStartVersion.getValue(15).forEach { connection.runSql(it) }
            connection.runSql(
                "INSERT INTO subscriptions VALUES (12, 'Third', " +
                    "(SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM subscriptions))",
            )

            val names = connection.query("SELECT name FROM subscriptions ORDER BY sortOrder, id").map { it["name"] }
            assertEquals(listOf("First", "Second", "Third"), names)
        }
    }

    @Test
    fun alwaysProxiedMigrationPreservesExistingSelections() {
        openInMemory().use { connection ->
            connection.runSql("CREATE TABLE app_bypass (packageName TEXT PRIMARY KEY, routeMode TEXT)")
            connection.runSql("INSERT INTO app_bypass VALUES ('forced', 'always_proxied'), ('regular', 'default_selected')")
            DatabaseMigrations.sqlByStartVersion.getValue(22).forEach { connection.runSql(it) }

            val rows = connection.query("SELECT packageName, routeMode, alwaysProxied FROM app_bypass ORDER BY packageName")
            assertEquals(
                listOf(
                    mapOf("packageName" to "forced", "routeMode" to "default_selected", "alwaysProxied" to "1"),
                    mapOf("packageName" to "regular", "routeMode" to "default_selected", "alwaysProxied" to "0"),
                ),
                rows,
            )
        }
    }

    /**
     * Opens a version 1 database file through Room itself, which runs every migration, validates
     * the result against the entities and identity hash, and fails rather than wiping the data.
     */
    @Test
    fun roomUpgradesAVersionOneDatabaseAndKeepsItsRows() = runTest {
        val file = temporaryFolder.root.resolve(AppDatabase.DATABASE_NAME)
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.applyVersionOneSchema()
            connection.runSql("INSERT INTO subscriptions (id, name, url, lastUpdated) VALUES (7, ' Kept ', 'https://example.com', 1)")
            connection.runSql("PRAGMA user_version = 1")
        }

        val database = Room.databaseBuilder<AppDatabase>(file.path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*DatabaseMigrations.all)
            .addCallback(AppDatabase.VALUE_VALIDATION_CALLBACK)
            .build()
        try {
            assertTrue(DatabaseOpenChecker(database, NoOpAppLogger).canRead())
            val subscription = database.subscriptionDao().getAll().single()
            assertEquals(7L, subscription.id)
            // The value validation callback ran on open and trimmed the name.
            assertEquals("Kept", subscription.name)
            assertEquals(7, subscription.sortOrder)
        } finally {
            database.close()
        }
    }

    /**
     * Room accepts any database default when the entity declares none, so the chain is only
     * required to match a default that the exported schema actually specifies.
     */
    private fun assertColumnsMatch(table: String, expected: Map<String, Column>, actual: Map<String, Column>) {
        assertEquals("Columns of $table", expected.keys, actual.keys)
        expected.forEach { (name, expectedColumn) ->
            val actualColumn = actual.getValue(name)
            assertEquals("$table.$name type", expectedColumn.type, actualColumn.type)
            assertEquals("$table.$name nullability", expectedColumn.notNull, actualColumn.notNull)
            assertEquals("$table.$name primary key position", expectedColumn.primaryKeyPosition, actualColumn.primaryKeyPosition)
            if (expectedColumn.defaultValue != null) {
                assertEquals("$table.$name default", expectedColumn.defaultValue, actualColumn.defaultValue)
            }
        }
    }

    /**
     * The schema as it existed at version 1.
     *
     * Versions 1 to 3 predate this repository, so `subscriptions` and `app_bypass` are derived by
     * removing from the oldest committed entity definitions exactly the columns that
     * [DatabaseMigrations] adds in steps 1 to 3. No migration has ever touched `servers`, so its
     * baseline is the current definition; this test consequently cannot detect a `servers`
     * migration that was needed but never written.
     */
    private fun SQLiteConnection.applyVersionOneSchema() {
        runSql(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`lastUpdated` INTEGER NOT NULL)",
        )
        runSql(
            "CREATE TABLE IF NOT EXISTS `servers` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`subscriptionId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`protocol` TEXT NOT NULL, " +
                "`address` TEXT NOT NULL, " +
                "`port` INTEGER NOT NULL, " +
                "`configJson` TEXT NOT NULL, " +
                "`latencyMs` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`subscriptionId`) REFERENCES `subscriptions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        runSql("CREATE INDEX IF NOT EXISTS `index_servers_subscriptionId` ON `servers` (`subscriptionId`)")
        runSql(
            "CREATE TABLE IF NOT EXISTS `app_bypass` (" +
                "`packageName` TEXT NOT NULL, " +
                "`uid` INTEGER NOT NULL, " +
                "`excluded` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`))",
        )
    }

    private fun readCurrentSchema(): SchemaFile {
        val directory = File(
            requireNotNull(System.getProperty("room.schemaLocation")) {
                "room.schemaLocation is not set; the Gradle test task must provide it"
            },
        )
        val exported = directory.resolve(SCHEMA_SUBDIRECTORY).listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }
        assertTrue(
            "No exported Room schema found in $directory. Run a build so KSP regenerates it.",
            exported.isNotEmpty(),
        )
        val latest = exported.maxBy { it.nameWithoutExtension.toInt() }
        return json.decodeFromString<SchemaFile>(latest.readText())
    }

    private fun SQLiteConnection.runSql(sql: String) = execSQL(sql)

    private fun SQLiteConnection.tableNames(): Set<String> = query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
    ).mapTo(mutableSetOf()) { it.text("name") }

    private fun SQLiteConnection.columns(table: String): Map<String, Column> = query("PRAGMA table_info(`$table`)")
        .associate { row ->
            row.text("name") to Column(
                type = row.text("type").uppercase(),
                notNull = row.getValue("notnull") != "0",
                defaultValue = row.getValue("dflt_value"),
                primaryKeyPosition = row.text("pk").toInt(),
            )
        }

    // Implicit indices are excluded because SQLite names them after the table they were created
    // on; primary keys are compared through table_info instead, which is also what Room does.
    private fun SQLiteConnection.indices(table: String): Map<String, Index> = query("PRAGMA index_list(`$table`)")
        .filterNot { it.text("name").startsWith("sqlite_autoindex_") }
        .associate { row ->
            val name = row.text("name")
            val columns = query("PRAGMA index_info(`$name`)")
                .sortedBy { it.text("seqno").toInt() }
                .map { it.text("name") }
            name to Index(unique = row.getValue("unique") != "0", columns = columns)
        }

    private fun SQLiteConnection.foreignKeys(table: String): Set<ForeignKey> = query("PRAGMA foreign_key_list(`$table`)")
        .mapTo(mutableSetOf()) { row ->
            ForeignKey(
                referencedTable = row.text("table"),
                column = row.text("from"),
                referencedColumn = row.text("to"),
                onUpdate = row.text("on_update"),
                onDelete = row.text("on_delete"),
            )
        }

    private fun String.forTable(tableName: String) = replace("\${TABLE_NAME}", tableName)

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private data class Index(val unique: Boolean, val columns: List<String>)

    private data class ForeignKey(
        val referencedTable: String,
        val column: String,
        val referencedColumn: String,
        val onUpdate: String,
        val onDelete: String,
    )

    @Serializable
    private data class SchemaFile(val database: SchemaDatabase)

    @Serializable
    private data class SchemaDatabase(val version: Int, val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaEntity(
        val tableName: String,
        val createSql: String,
        val indices: List<SchemaIndex> = emptyList(),
    )

    @Serializable
    private data class SchemaIndex(val createSql: String)

    private companion object {
        const val SCHEMA_SUBDIRECTORY = "com.material.xray.core.database.AppDatabase"
        val json = Json { ignoreUnknownKeys = true }
    }
}
