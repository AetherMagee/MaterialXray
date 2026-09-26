package com.material.xray.core.xray

/**
 * Leftovers of an earlier install. Removing the app without a disconnect takes its data with it but
 * leaves the policy routing rules its root shell added behind, so the next install reads rules
 * carrying its own production signature as a foreign mark namespace, fails with
 * [TproxyCompatibility.Reason.MarkNamespaceConflict] and silently demotes the backend to TUN.
 *
 * The cleanup is deliberately narrow: only rules matching the production signature exactly are ours
 * to delete, and a route table is flushed only when the rule numbered it and the number is not
 * reserved.
 */

/** Emitted instead of cleaning up when a runtime state file appeared before the commands ran. */
internal const val RUNTIME_STATE_MARKER = "mx-runtime-state-present"

/** Route tables the kernel reserves for itself; flushing one would take the device's routing down. */
private val RESERVED_ROUTE_TABLES = setOf(0, 253, 254, 255)

/** Rules carrying the app's exact production signature: same priority and the same fwmark pair. */
internal fun staleTproxyRules(ruleOutput: String): List<FwmarkRule> = overlappingFwmarkRules(
    ruleOutput,
    TproxyCompatibilityDetector.MARK_PREFIX,
    TproxyCompatibilityDetector.MARK_MASK,
).filter { rule ->
    rule.priority == TproxyManager.RULE_PRIORITY &&
        rule.value == TproxyCompatibilityDetector.MARK_PREFIX.toUInt() &&
        rule.mask == TproxyCompatibilityDetector.MARK_MASK.toUInt()
}

/**
 * Commands that remove the [rules][staleTproxyRules] left behind by a dead install. Every matching
 * rule of a family is deleted in a loop, so duplicates from several dead installs go together. A
 * route table is flushed only when the rule numbered it and the number is not reserved; a named or
 * reserved table keeps its routes rather than risking foreign ones.
 */
internal fun staleTproxyCleanupCommands(
    ipv4Rules: List<FwmarkRule>,
    ipv6Rules: List<FwmarkRule>,
): List<String> {
    val mark = "0x${TproxyCompatibilityDetector.MARK_PREFIX.toString(16)}/" +
        "0x${TproxyCompatibilityDetector.MARK_MASK.toString(16)}"
    val priority = TproxyManager.RULE_PRIORITY
    return buildList {
        if (ipv4Rules.isNotEmpty()) {
            add("while ip rule del fwmark $mark pref $priority 2>/dev/null; do :; done")
        }
        if (ipv6Rules.isNotEmpty()) {
            add("while ip -6 rule del fwmark $mark pref $priority 2>/dev/null; do :; done")
        }
        (ipv4Rules + ipv6Rules)
            .mapNotNull(FwmarkRule::table)
            .filterNot(RESERVED_ROUTE_TABLES::contains)
            .distinct()
            .sorted()
            .forEach { table ->
                add("ip route flush table $table 2>/dev/null || true")
                add("ip -6 route flush table $table 2>/dev/null || true")
            }
    }
}

/**
 * Wraps the [commands] in a guard that only runs while [runtimeStatePath] does not exist, so rules
 * a connection claimed while the cleanup was being prepared keep their owner.
 */
internal fun guardStaleTproxyCleanup(commands: List<String>, runtimeStatePath: String): String = "if [ -e \"$runtimeStatePath\" ]; then echo $RUNTIME_STATE_MARKER; else ${commands.joinToString("; ")}; fi"
