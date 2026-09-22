package com.material.xray.core.network

import java.io.File
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAndroidCaFallbackTest {

    @Test
    fun `uses the system trust manager without consulting the bundle when validation succeeds`() {
        val system = RecordingTrustManager()
        val bundle = RecordingTrustManager()

        AdditiveX509TrustManager(system, bundle).checkServerTrusted(emptyArray(), "RSA")

        assertEquals(1, system.serverChecks)
        assertEquals(0, bundle.serverChecks)
    }

    @Test
    fun `consults the bundle after system validation rejects a server chain`() {
        val system = RecordingTrustManager(serverFailure = CertificateException("Unknown root"))
        val bundle = RecordingTrustManager()

        AdditiveX509TrustManager(system, bundle).checkServerTrusted(emptyArray(), "RSA")

        assertEquals(1, system.serverChecks)
        assertEquals(1, bundle.serverChecks)
    }

    @Test
    fun `rejects a server chain when neither trust manager accepts it`() {
        val system = RecordingTrustManager(serverFailure = CertificateException("Unknown system root"))
        val bundle = RecordingTrustManager(serverFailure = CertificateException("Unknown bundled root"))

        assertThrows(CertificateException::class.java) {
            AdditiveX509TrustManager(system, bundle).checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun `Mozilla bundle contains the chain anchor missing from the Android 7 device`() {
        val trustManager = File("src/main/res/raw/mozilla_ca_bundle.pem").inputStream().use(::loadX509TrustManager)

        assertEquals(121, trustManager.acceptedIssuers.size)
        assertTrue(
            trustManager.acceptedIssuers.any { certificate ->
                certificate.subjectX500Principal.name.contains("CN=ISRG Root X1")
            },
        )
    }

    @Test
    fun `fallback is limited to Android 7`() {
        assertTrue(shouldUseBundledCaFallback(24))
        assertTrue(shouldUseBundledCaFallback(25))
        assertFalse(shouldUseBundledCaFallback(26))
    }

    private class RecordingTrustManager(
        private val serverFailure: CertificateException? = null,
    ) : X509TrustManager {
        var serverChecks = 0
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            serverChecks++
            serverFailure?.let { throw it }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
