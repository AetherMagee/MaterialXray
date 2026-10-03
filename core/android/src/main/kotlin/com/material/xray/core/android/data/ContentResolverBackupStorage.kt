package com.material.xray.core.android.data

import android.content.Context
import androidx.core.net.toUri
import com.material.xray.data.platform.BackupStorage
import java.io.InputStream
import java.io.OutputStream
import org.koin.core.annotation.Singleton

/** [BackupStorage] on the `content://` URIs the system file picker returns. */
@Singleton(binds = [BackupStorage::class])
class ContentResolverBackupStorage(private val context: Context) : BackupStorage {
    override fun openInput(locator: String): InputStream? = context.contentResolver.openInputStream(locator.toUri())

    override fun openOutput(locator: String): OutputStream? = context.contentResolver.openOutputStream(locator.toUri())
}
