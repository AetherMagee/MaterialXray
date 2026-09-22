package com.material.xray.core.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

internal fun shouldUseBundledCaFallback(sdkInt: Int): Boolean = sdkInt in 24..25

internal fun OkHttpClient.Builder.addBundledCaFallback(certificateBundle: InputStream): OkHttpClient.Builder = apply {
    val trustManager = AdditiveX509TrustManager(
        system = loadSystemTrustManager(),
        fallback = loadX509TrustManager(certificateBundle),
    )
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    sslSocketFactory(sslContext.socketFactory, trustManager)
}

internal class AdditiveX509TrustManager(
    private val system: X509TrustManager,
    private val fallback: X509TrustManager,
) : X509TrustManager {
    private val issuers = system.acceptedIssuers + fallback.acceptedIssuers

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            system.checkClientTrusted(chain, authType)
        } catch (_: CertificateException) {
            fallback.checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (_: CertificateException) {
            fallback.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = issuers.copyOf()
}

internal fun loadX509TrustManager(certificateBundle: InputStream): X509TrustManager {
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val pem = certificateBundle.bufferedReader(StandardCharsets.US_ASCII).use { it.readText() }
    val certificates = PEM_CERTIFICATE_PATTERN.findAll(pem).map { match ->
        ByteArrayInputStream(match.value.toByteArray(StandardCharsets.US_ASCII)).use { input ->
            certificateFactory.generateCertificate(input) as X509Certificate
        }
    }.toList()
    require(certificates.isNotEmpty()) { "Bundled CA store contains no certificates" }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate ->
            setCertificateEntry("bundled-ca-$index", certificate)
        }
    }
    return loadX509TrustManager(keyStore)
}

private fun loadSystemTrustManager(): X509TrustManager = loadX509TrustManager(null)

private fun loadX509TrustManager(keyStore: KeyStore?): X509TrustManager {
    val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(keyStore)
        trustManagers.filterIsInstance<X509TrustManager>()
    }
    check(trustManagers.size == 1) { "Expected exactly one X.509 trust manager" }
    return trustManagers.single()
}

private val PEM_CERTIFICATE_PATTERN = Regex(
    "-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
    RegexOption.DOT_MATCHES_ALL,
)
