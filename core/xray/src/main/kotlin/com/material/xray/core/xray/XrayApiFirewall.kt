package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.FirewallCommands.IPV4 as IPTABLES

class XrayApiFirewall(
    private val execute: suspend (String) -> RootShell.Result,
) {
    constructor(shell: RootShell) : this(execute = { command -> shell.execute(command) })

    suspend fun apply(port: Int, appUid: Int): Boolean {
        if (port !in 1..65_535 || appUid <= 0) return false
        val chainA = chainName(appUid, "a")
        val chainB = chainName(appUid, "b")
        return execute(buildApplyCommand(chainA, chainB, port, appUid)).isSuccess
    }

    fun removeCommand(appUid: Int): String {
        require(appUid > 0)
        return buildString {
            append(shellHelpers())
            append("; refresh_ruleset || exit 1; status=0")
            append("; remove_chain ${chainName(appUid, "a")} || status=1")
            append("; remove_chain ${chainName(appUid, "b")} || status=1")
            append("; exit \$status")
        }
    }

    internal fun buildApplyCommand(chainA: String, chainB: String, port: Int, appUid: Int): String = buildString {
        append(shellHelpers())
        append("; refresh_ruleset || exit 1")
        append("; if ! has_jump $chainA && ! has_jump $chainB && ! chain_exists $chainA && ! chain_exists $chainB")
        append("; then if ${freshInstallCommand(chainA, port, appUid)}; then exit 0; fi; fi")
        append("; if has_jump $chainA; then active=$chainA; replacement=$chainB")
        append("; elif has_jump $chainB; then active=$chainB; replacement=$chainA")
        append("; else active=''; replacement=$chainA; fi")
        append("; remove_chain \"\$replacement\" || exit 1")
        append("; setup_replacement() { if ! $IPTABLES -N \"\$replacement\"")
        append(" || ! $IPTABLES -A \"\$replacement\" -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port")
        append(" -m owner --uid-owner $appUid -j ACCEPT")
        append(" || ! $IPTABLES -A \"\$replacement\" -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port -j REJECT")
        append("; then")
        append(" return 1; fi; $IPTABLES -I OUTPUT 1 -j \"\$replacement\"; }")
        append("; if ! setup_replacement; then remove_chain \"\$replacement\"; exit 1; fi")
        append("; [ -z \"\$active\" ] || remove_chain \"\$active\"")
    }

    private fun freshInstallCommand(chain: String, port: Int, appUid: Int): String = FirewallRestoreBatch(
        tool = IPTABLES,
        table = "filter",
        commands = listOf(
            "$IPTABLES -t filter -N $chain",
            "$IPTABLES -t filter -A $chain -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port " +
                "-m owner --uid-owner $appUid -j ACCEPT",
            "$IPTABLES -t filter -A $chain -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port -j REJECT",
            "$IPTABLES -t filter -I OUTPUT 1 -j $chain",
        ),
    ).command()

    private fun shellHelpers(): String = "refresh_ruleset() { ruleset=\$($IPTABLES -S) || return 1; }" +
        "; newline='\n'" +
        "; has_jump() { case \"\$newline\$ruleset\$newline\" in *\"\$newline-A OUTPUT -j \$1\$newline\"*) true;; *) false;; esac; }" +
        "; chain_exists() { case \"\$newline\$ruleset\$newline\" in *\"\$newline-N \$1\$newline\"*) true;; *) false;; esac; }" +
        "; remove_chain() { " +
        "refresh_ruleset || return 1" +
        "; while has_jump \"\$1\"; do $IPTABLES -D OUTPUT -j \"\$1\" || return 1" +
        "; refresh_ruleset || return 1; done" +
        "; if chain_exists \"\$1\"; then $IPTABLES -F \"\$1\" && $IPTABLES -X \"\$1\" || return 1" +
        "; refresh_ruleset || return 1; fi; }"

    private fun chainName(appUid: Int, slot: String): String = "mxray_api_${appUid}_$slot"
}
