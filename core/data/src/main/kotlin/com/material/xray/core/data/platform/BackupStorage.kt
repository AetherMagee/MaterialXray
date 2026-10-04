package com.material.xray.core.data.platform

import java.io.InputStream
import java.io.OutputStream

/**
 * Opens the files the user picks for backups. A locator is whatever the platform's file picker
 * returns, as a string: a `content://` URI on Android.
 */
interface BackupStorage {
    /** Opens [locator] for reading; null when the platform cannot open it. */
    fun openInput(locator: String): InputStream?

    /** Opens [locator] for writing, replacing its contents; null when the platform cannot open it. */
    fun openOutput(locator: String): OutputStream?
}
