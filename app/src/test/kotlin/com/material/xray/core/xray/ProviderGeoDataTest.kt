package com.material.xray.core.xray

import com.material.xray.model.RoutingGeoData
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderGeoDataTest {
    private val geoipUrl = "https://cdn.example/geoip.dat"
    private val geositeUrl = "https://cdn.example/geosite.dat"
    private val geoData = RoutingGeoData(geoipUrl = geoipUrl, geositeUrl = geositeUrl)
    private val geoipFile = providerGeoDataFileName(geoipUrl)
    private val geositeFile = providerGeoDataFileName(geositeUrl)

    @Test
    fun `reads category codes from geodata list`() {
        val bytes = geoDataList(
            entry(stringField(1, "win-spy"), stringField(2, "ignored domain payload")),
            // Field order is not guaranteed, so the code may follow other fields.
            entry(stringField(3, "x"), varintField(4, 300), stringField(1, "WHITELIST")),
        )

        assertEquals(setOf("WIN-SPY", "WHITELIST"), readGeoDataCodes(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `rejects data that is not a geodata list`() {
        val html = "<html>captive portal</html>".toByteArray()

        assertThrows(IOException::class.java) { readGeoDataCodes(ByteArrayInputStream(html)) }
    }

    @Test
    fun `rejects oversized code length instead of allocating it`() {
        val bytes = lengthDelimited(1, varint(0x0A) + varint(Long.MAX_VALUE shr 1))

        assertThrows(IOException::class.java) { readGeoDataCodes(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `rejects truncated geodata`() {
        val bytes = geoDataList(entry(stringField(1, "WIN-SPY")))

        assertThrows(IOException::class.java) {
            readGeoDataCodes(ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
        }
    }

    @Test
    fun `provider entries point at the provider file when it defines them`() {
        val rule = providerRule(domains = listOf("geosite:win-spy", "geosite:category-ads@ads", "domain:example.com"))

        val resolution = resolve(listOf(rule), providerGeosite = setOf("WIN-SPY", "CATEGORY-ADS"))

        assertEquals(
            listOf("ext:$geositeFile:win-spy", "ext:$geositeFile:category-ads@ads", "domain:example.com"),
            resolution.rules.single().domains,
        )
        assertEquals(setOf(geositeUrl), resolution.usedUrls)
        assertTrue(resolution.unavailableUrls.isEmpty())
        assertTrue(resolution.droppedEntries.isEmpty())
    }

    @Test
    fun `negated ip entries keep their negation`() {
        val rule = providerRule(ips = listOf("!geoip:direct", "geoip:!private"))

        val resolution = resolve(listOf(rule), providerGeoip = setOf("DIRECT", "PRIVATE"))

        assertEquals(listOf("!ext:$geoipFile:direct", "ext:$geoipFile:!private"), resolution.rules.single().ips)
    }

    @Test
    fun `missing provider file falls back to default data and drops the rest`() {
        val rule = providerRule(
            domains = listOf("geosite:category-ru", "geosite:whitelist"),
            ips = listOf("geoip:private", "geoip:direct"),
        )

        val resolution = resolve(
            listOf(rule),
            providerGeoip = null,
            providerGeosite = null,
            defaultGeoip = setOf("PRIVATE"),
            defaultGeosite = setOf("CATEGORY-RU"),
        )

        val resolved = resolution.rules.single()
        assertEquals(listOf("geosite:category-ru"), resolved.domains)
        assertEquals(listOf("geoip:private"), resolved.ips)
        assertEquals(setOf(geoipUrl, geositeUrl), resolution.unavailableUrls)
        assertEquals(listOf("geosite:whitelist", "geoip:direct"), resolution.droppedEntries)
    }

    @Test
    fun `rule left without conditions is dropped instead of matching all traffic`() {
        val blockRule = providerRule(outboundTag = "block", domains = listOf("geosite:win-spy"))

        val resolution = resolve(listOf(blockRule), providerGeosite = null)

        assertTrue(resolution.rules.isEmpty())
    }

    @Test
    fun `AND rule that loses a whole condition is dropped`() {
        val rule = providerRule(
            domains = listOf("geosite:win-spy"),
            ips = listOf("geoip:private"),
            operator = RoutingRuleOperator.AND,
        )

        val resolution = resolve(listOf(rule), providerGeosite = null, defaultGeoip = setOf("PRIVATE"))

        assertTrue(resolution.rules.isEmpty())
    }

    @Test
    fun `OR rule keeps its remaining conditions`() {
        val rule = providerRule(domains = listOf("geosite:win-spy"), ips = listOf("geoip:private"))

        val resolution = resolve(listOf(rule), providerGeosite = null, defaultGeoip = setOf("PRIVATE"))

        val resolved = resolution.rules.single()
        assertTrue(resolved.domains.isEmpty())
        assertEquals(listOf("geoip:private"), resolved.ips)
    }

    @Test
    fun `rules without provider geodata are left alone`() {
        val custom = RoutingRule(id = "custom", name = "Custom", outboundTag = "block", domains = listOf("geosite:nope"))

        val resolution = resolve(listOf(custom), defaultGeosite = emptySet())

        assertEquals(listOf(custom), resolution.rules)
        assertTrue(resolution.droppedEntries.isEmpty())
    }

    @Test
    fun `fresh file is not downloaded again`() {
        val now = PROVIDER_GEO_DATA_STALE_AFTER_MS * 2

        assertFalse(needsDownload(lastModified = now - 1, now = now, force = true))
    }

    @Test
    fun `missing or stale file waits out a recent failure unless forced`() {
        val now = PROVIDER_GEO_DATA_STALE_AFTER_MS * 2
        val recentFailure = ProviderGeoDataFailure(now - PROVIDER_GEO_DATA_RETRY_AFTER_MS + 1, throughTunnel = false)
        val oldFailure = ProviderGeoDataFailure(now - PROVIDER_GEO_DATA_RETRY_AFTER_MS, throughTunnel = false)
        val stale = now - PROVIDER_GEO_DATA_STALE_AFTER_MS

        assertTrue(needsDownload(now = now))
        assertFalse(needsDownload(lastFailure = recentFailure, now = now))
        assertTrue(needsDownload(lastFailure = recentFailure, now = now, force = true))
        assertTrue(needsDownload(lastFailure = oldFailure, now = now))
        assertFalse(needsDownload(lastModified = stale, lastFailure = recentFailure, now = now))
        assertTrue(needsDownload(lastModified = stale, now = now))
    }

    @Test
    fun `direct failure does not delay an attempt through the tunnel`() {
        val now = PROVIDER_GEO_DATA_STALE_AFTER_MS * 2
        val directFailure = ProviderGeoDataFailure(now - 1, throughTunnel = false)
        val tunnelFailure = ProviderGeoDataFailure(now - 1, throughTunnel = true)

        assertTrue(needsDownload(lastFailure = directFailure, now = now, throughTunnel = true))
        assertFalse(needsDownload(lastFailure = tunnelFailure, now = now, throughTunnel = true))
        assertFalse(needsDownload(lastFailure = tunnelFailure, now = now, throughTunnel = false))
    }

    @Test
    fun `file name is stable per url`() {
        assertEquals(providerGeoDataFileName(geoipUrl), providerGeoDataFileName(geoipUrl))
        assertFalse(providerGeoDataFileName(geoipUrl) == providerGeoDataFileName(geositeUrl))
        assertTrue(Regex("provider-[0-9a-f]{16}\\.dat").matches(providerGeoDataFileName(geoipUrl)))
    }

    @Test
    fun `notice reports downloads, compatibility mode and data ready to apply`() {
        val urls = setOf(geoipUrl, geositeUrl)
        val all = setOf(geoipFile, geositeFile)

        assertEquals(
            ProviderGeoDataNotice.Downloading,
            providerGeoDataNotice(urls, ProviderGeoDataState(downloading = setOf(geoipUrl)), connected = true),
        )
        assertEquals(
            ProviderGeoDataNotice.CompatibilityMode,
            providerGeoDataNotice(urls, ProviderGeoDataState(availableFiles = setOf(geoipFile)), connected = false),
        )
        val startedWithout = ProviderGeoDataState(availableFiles = all, activeUnavailableUrls = setOf(geositeUrl))
        assertEquals(ProviderGeoDataNotice.ReadyToApply, providerGeoDataNotice(urls, startedWithout, connected = true))
        assertNull(providerGeoDataNotice(urls, startedWithout, connected = false))
        assertNull(providerGeoDataNotice(urls, ProviderGeoDataState(availableFiles = all), connected = true))
        // Data the running connection lacked belongs to another provider once the selection changes.
        val otherUrl = "https://other.example/geosite.dat"
        val otherAvailable = startedWithout.copy(availableFiles = all + providerGeoDataFileName(otherUrl))
        assertNull(providerGeoDataNotice(setOf(otherUrl), otherAvailable, connected = true))
    }

    private fun needsDownload(
        lastModified: Long? = null,
        lastFailure: ProviderGeoDataFailure? = null,
        now: Long,
        force: Boolean = false,
        throughTunnel: Boolean = false,
    ) = providerGeoDataNeedsDownload(lastModified, lastFailure, now, force, throughTunnel)

    private fun providerRule(
        outboundTag: String = "direct",
        domains: List<String> = emptyList(),
        ips: List<String> = emptyList(),
        operator: RoutingRuleOperator = RoutingRuleOperator.OR,
    ) = RoutingRule(
        id = "happ-$outboundTag",
        name = "Provider",
        outboundTag = outboundTag,
        domains = domains,
        ips = ips,
        operator = operator,
        geoData = geoData,
    )

    private fun resolve(
        rules: List<RoutingRule>,
        providerGeoip: Set<String>? = emptySet(),
        providerGeosite: Set<String>? = emptySet(),
        defaultGeoip: Set<String> = emptySet(),
        defaultGeosite: Set<String> = emptySet(),
    ) = resolveProviderGeoData(rules, defaultGeoip, defaultGeosite) { url ->
        when (url) {
            geoipUrl -> providerGeoip
            geositeUrl -> providerGeosite
            else -> null
        }
    }

    private fun geoDataList(vararg entries: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        entries.forEach { write(lengthDelimited(1, it)) }
    }.toByteArray()

    private fun entry(vararg fields: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        fields.forEach(::write)
    }.toByteArray()

    private fun stringField(number: Int, value: String) = lengthDelimited(number, value.toByteArray())

    private fun varintField(number: Int, value: Long) = varint((number shl 3).toLong()) + varint(value)

    private fun lengthDelimited(number: Int, payload: ByteArray) = varint(((number shl 3) or 2).toLong()) + varint(payload.size.toLong()) + payload

    private fun varint(value: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (remaining >= 0x80) {
            output.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
        return output.toByteArray()
    }
}
