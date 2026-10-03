package com.material.xray.data.repository

import com.material.xray.model.DnsPreset
import com.material.xray.model.canonicalDnsServers
import com.material.xray.model.dnsPresetFor
import com.material.xray.model.isEncryptedDnsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDnsDefaultsTest {

    @Test
    fun `the shipped defaults are presets the DNS screen can name`() {
        assertEquals(DnsPreset.Cloudflare, dnsPresetFor(SettingsRepository.DEFAULT_DNS_SERVERS))
        assertTrue(isEncryptedDnsValue(SettingsRepository.DEFAULT_DNS_SERVERS))
        assertEquals(DnsPreset.Yandex, dnsPresetFor(SettingsRepository.DEFAULT_DOMESTIC_DNS_SERVERS))
        assertNull(canonicalDnsServers(SettingsRepository.DEFAULT_DNS_SERVERS))
        assertNull(canonicalDnsServers(SettingsRepository.DEFAULT_DOMESTIC_DNS_SERVERS))
    }
}
