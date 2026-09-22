package com.material.xray.ui.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingDomainFormatterTest {

    @Test
    fun `strips domain prefix from FQDNs and limits list previews`() {
        val preview = routingDomainPreview(
            listOf(
                "domain:2gis.com",
                "domain:kontur.host",
                "domain:megamarket.tech",
                "domain:reviews.2gis.com",
            ),
        )

        assertEquals(listOf("2gis.com", "kontur.host"), preview.visibleValues)
        assertEquals(2, preview.omittedCount)
    }

    @Test
    fun `keeps non-FQDN domain rules and other Xray matchers intact`() {
        val preview = routingDomainPreview(
            listOf("domain:ru", "geosite:private", "domain:*.example.com", "domain:127.0.0.1"),
            maxVisible = 5,
        )

        assertEquals(
            listOf("domain:ru", "geosite:private", "domain:*.example.com", "domain:127.0.0.1"),
            preview.visibleValues,
        )
        assertEquals(0, preview.omittedCount)
    }

    @Test
    fun `cleans every FQDN without limiting full viewer values`() {
        val domains = routingDomainDisplayValues(
            listOf("domain:one.example", "domain:two.example", "domain:three.example"),
        )

        assertEquals(listOf("one.example", "two.example", "three.example"), domains)
    }

    @Test
    fun `limits generic routing value previews without changing values`() {
        val preview = routingListPreview(listOf("10.0.0.0/8", "geoip:private", "geoip:ru"))

        assertEquals(listOf("10.0.0.0/8", "geoip:private"), preview.visibleValues)
        assertEquals(1, preview.omittedCount)
    }
}
