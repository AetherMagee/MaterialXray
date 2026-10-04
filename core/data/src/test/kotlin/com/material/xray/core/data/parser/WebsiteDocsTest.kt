package com.material.xray.core.data.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keeps the provider docs on materialxray.app in step with what the client actually sends,
 * reads and parses. Each page's first table is the list a provider reads, so it is compared
 * as a whole: a name missing there is undocumented, and an extra one is gone from the code.
 */
class WebsiteDocsTest {

    @Test
    fun `request headers page lists exactly the headers the client sends`() {
        assertEquals(
            SubscriptionStandardHeaders.requestHeaderNames.lowercaseSet(),
            firstTableColumn("providers/request-headers.md").lowercaseSet(),
        )
    }

    @Test
    fun `response headers page lists exactly the headers the client reads`() {
        assertEquals(
            SubscriptionStandardHeaders.responseHeaderNames.lowercaseSet(),
            firstTableColumn("providers/response-headers.md").lowercaseSet(),
        )
    }

    @Test
    fun `share links page lists exactly the schemes the parser accepts`() {
        val rows = firstTableRows("reference/share-links.md")
            .map { cells -> cells[0].codeSpans().filter { it.endsWith("://") } to cells }

        assertEquals(
            ShareLinkParser.directSchemes,
            rows.filter { (_, cells) -> cells[1].contains(SUPPORTED) }.flatMap { it.first }.toSet(),
        )
        assertEquals(
            ShareLinkParser.subscriptionSchemes,
            rows.filter { (_, cells) -> cells[2].contains(SUPPORTED) }.flatMap { it.first }.toSet(),
        )
    }

    private fun firstTableColumn(page: String): List<String> = firstTableRows(page).flatMap { it[0].codeSpans() }

    /** The cells of the first Markdown table on [page], without its header and separator rows. */
    private fun firstTableRows(page: String): List<List<String>> = File(DOCS_DIR, page).readLines()
        .dropWhile { !it.startsWith("|") }
        .takeWhile { it.startsWith("|") }
        .drop(2)
        .map { row -> row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() } }

    private fun String.codeSpans(): List<String> = codeSpan.findAll(this).map { it.groupValues[1] }.toList()

    private fun Iterable<String>.lowercaseSet(): Set<String> = mapTo(sortedSetOf()) { it.lowercase() }

    private companion object {
        // Gradle runs unit tests from the module directory; core/data/build.gradle.kts declares
        // this directory as a test input so docs edits rerun the test.
        val DOCS_DIR = File("../../website/src/content/docs/docs")
        const val SUPPORTED = "✅"
        val codeSpan = Regex("`([^`]+)`")
    }
}
