package com.material.xray

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDeepLinkTest {

    @Test
    fun `extracts subscription URL from mxray add link`() {
        assertEquals(
            "https://sub.pierdoling.org/3uFMV9SvEcdJeFsw",
            subscriptionLinkFromDeepLink("mxray://add/https://sub.pierdoling.org/3uFMV9SvEcdJeFsw"),
        )
    }

    @Test
    fun `preserves query parameters in subscription URL`() {
        assertEquals(
            "https://example.com/sub?token=one&client=mxray",
            subscriptionLinkFromDeepLink("mxray://add/https://example.com/sub?token=one&client=mxray"),
        )
    }

    @Test
    fun `rejects unsupported deep links`() {
        assertNull(subscriptionLinkFromDeepLink("mxray://open/https://example.com/sub"))
        assertNull(subscriptionLinkFromDeepLink("mxray://add/ftp://example.com/sub"))
        assertNull(subscriptionLinkFromDeepLink("https://example.com/sub"))
    }

    @Test
    fun `website deeplink examples behave as documented`() {
        // Gradle runs unit tests from the module directory.
        val page = File("../website/src/content/docs/docs/providers/deeplinks.md").readText()
        val examples = Regex("""mxray://add/([^\s"'`<]+)""").findAll(page).toList()
        assertTrue("no deeplink examples found", examples.isNotEmpty())

        examples.forEach { example ->
            val (link, embedded) = example.groupValues
            // The page shows a percent-encoded link as the one that won't work.
            val expected = embedded.takeUnless { it.contains('%') }
            assertEquals(link, expected, subscriptionLinkFromDeepLink(link))
        }
    }
}
