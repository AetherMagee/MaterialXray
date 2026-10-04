package com.material.xray.core.connection

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface RootCertificateBundle {
    /** Makes sure [file] holds a CA bundle the core can be started with. */
    suspend fun prepare(file: File)
}

/** [refreshScope] must run off the main thread, since the refresh parses and writes the bundle. */
class AndroidRootCertificateBundle(
    private val refreshScope: CoroutineScope,
    private val refreshDelayMillis: Long = CACHED_BUNDLE_REFRESH_DELAY_MS,
    private val loadBundledCertificates: () -> List<ByteArray> = { emptyList() },
    private val loadCertificates: () -> List<ByteArray> = ::loadAndroidCaCertificates,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RootCertificateBundle {
    private val refreshedInProcess = AtomicBoolean()

    // Parsing a few hundred certificates is cheap on an idle device, but a process throttled
    // during boot once spent over 20 s on it before the tunnel could come up. A bundle written by
    // an earlier connection is therefore used as is, and rebuilt once per process after the
    // connection that asked for it has had time to settle, so CA store changes reach the next one.
    override suspend fun prepare(file: File) {
        val cached = withContext(ioDispatcher) { file.isFile && file.length() > 0L }
        if (!cached) {
            withContext(ioDispatcher) { write(file) }
            refreshedInProcess.set(true)
            return
        }
        if (refreshedInProcess.compareAndSet(false, true)) {
            refreshScope.launch {
                delay(refreshDelayMillis)
                // The cached bundle stays in place when this fails, and the next process retries.
                runCatching { write(file) }
            }
        }
    }

    private fun write(file: File) {
        val certificates = loadCertificates() + loadBundledCertificates()
        require(certificates.isNotEmpty()) { "Android CA store contains no certificates" }
        val pem = buildString {
            certificates.forEach { certificate ->
                appendLine("-----BEGIN CERTIFICATE-----")
                appendLine(PEM_ENCODER.encodeToString(certificate))
                appendLine("-----END CERTIFICATE-----")
            }
        }.toByteArray(StandardCharsets.US_ASCII)
        // Rewriting an unchanged bundle on every process start would only wear the flash.
        if (file.isFile && file.readBytes().contentEquals(pem)) return

        val parent = requireNotNull(file.parentFile) { "Certificate bundle must have a parent directory" }
        check(parent.isDirectory || parent.mkdirs()) { "Could not create certificate bundle directory: $parent" }
        val temporaryFile = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            temporaryFile.writeBytes(pem)
            // The root-mode core reads it as the app's group; the app's directories keep everyone else out.
            temporaryFile.setReadable(true, false)
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporaryFile.delete()
        }
    }

    private companion object {
        private const val CACHED_BUNDLE_REFRESH_DELAY_MS = 60_000L
        private val PEM_ENCODER = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
    }
}

private fun loadAndroidCaCertificates(): List<ByteArray> {
    val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val aliases = buildList {
        val enumeration = keyStore.aliases()
        while (enumeration.hasMoreElements()) {
            val alias = enumeration.nextElement()
            if (alias.startsWith("system:")) add(alias)
        }
    }
    return aliases.sorted().mapNotNull { alias ->
        (keyStore.getCertificate(alias) as? X509Certificate)?.encoded
    }
}
