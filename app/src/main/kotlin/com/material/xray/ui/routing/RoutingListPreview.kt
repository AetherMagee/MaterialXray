package com.material.xray.ui.routing

internal data class RoutingListPreview(
    val visibleValues: List<String>,
    val omittedCount: Int,
)

internal fun routingDomainPreview(
    domains: List<String>,
    maxVisible: Int = 2,
): RoutingListPreview = routingListPreview(
    values = routingDomainDisplayValues(domains),
    maxVisible = maxVisible,
)

internal fun routingDomainDisplayValues(domains: List<String>): List<String> = domains
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(String::withoutFqdnRulePrefix)

internal fun routingListPreview(
    values: List<String>,
    maxVisible: Int = 2,
): RoutingListPreview {
    require(maxVisible > 0)
    val displayValues = values.map(String::trim).filter(String::isNotEmpty)
    return RoutingListPreview(
        visibleValues = displayValues.take(maxVisible),
        omittedCount = (displayValues.size - maxVisible).coerceAtLeast(0),
    )
}

private fun String.withoutFqdnRulePrefix(): String {
    val prefix = "domain:"
    if (!startsWith(prefix, ignoreCase = true)) return this
    val domain = drop(prefix.length)
    return domain.takeIf(String::isFqdn) ?: this
}

private fun String.isFqdn(): Boolean {
    if (length !in 3..253) return false
    val labels = split('.')
    if (labels.size < 2 || labels.last().none(Char::isLetter)) return false
    return labels.all { label ->
        label.length in 1..63 &&
            label.first().isLetterOrDigit() &&
            label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}
