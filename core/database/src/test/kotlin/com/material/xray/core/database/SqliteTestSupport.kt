package com.material.xray.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** A private in-memory database on the bundled SQLite, the engine desktop builds ship. */
internal fun openInMemory(): SQLiteConnection = BundledSQLiteDriver().open(":memory:")

/** Every row [sql] returns, as column name to text value; SQLite renders numbers as text. */
internal fun SQLiteConnection.query(sql: String): List<Map<String, String?>> = prepare(sql).use { statement ->
    val names = List(statement.getColumnCount()) { statement.getColumnName(it) }
    buildList {
        while (statement.step()) {
            add(names.withIndex().associate { (index, name) -> name to statement.getTextOrNull(index) })
        }
    }
}

/** The number of rows the last INSERT, UPDATE or DELETE on this connection changed. */
internal fun SQLiteConnection.changes(): Int = prepare("SELECT changes()").use { statement ->
    statement.step()
    statement.getInt(0)
}

internal fun Map<String, String?>.text(column: String): String = requireNotNull(getValue(column)) { "$column is NULL" }

private fun androidx.sqlite.SQLiteStatement.getTextOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
