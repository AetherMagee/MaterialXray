package com.material.xray.core.xray

/** All firewall callers use the same bounded wait for Android's shared xtables lock. */
internal object FirewallCommands {
    const val IPV4 = "iptables -w 2"
    const val IPV6 = "ip6tables -w 2"
    val tools = listOf(IPV4, IPV6)

    fun restore(tool: String): String {
        require(tool in tools)
        return "${tool.substringBefore(' ')}-restore --noflush -w 2"
    }

    fun restoreAvailable(): String = tools.flatMap { tool ->
        val executable = tool.substringBefore(' ') + "-restore"
        listOf(
            "command -v $executable >/dev/null 2>&1",
            "$executable --help 2>&1 | grep -q -- '--noflush'",
            "$executable --help 2>&1 | grep -q -- '--wait'",
        )
    }.shellAnd()

    /** A failed inspection is an error, never evidence that cleanup succeeded. */
    fun absentChains(chains: List<String>, tables: List<String> = listOf("mangle", "filter"), tools: List<String> = this.tools): String {
        require(chains.all { it.matches(Regex("[A-Za-z0-9_]+")) })
        require(tables.all { it in listOf("mangle", "filter", "nat") })
        require(tools.all { it in this.tools })
        return buildList {
            add("newline='\n'")
            for (tool in tools) {
                for (table in tables) {
                    add("rules=\$($tool -t $table -S) || exit 1")
                    val patterns = chains.joinToString("|") { "*\"\$newline-N $it\$newline\"*" }
                    add("case \"\$newline\$rules\$newline\" in $patterns) exit 1;; esac")
                }
            }
            add("true")
        }.joinToString("; ")
    }
}

/** Braces keep an individual command's || or semicolons from masking an earlier failure. */
internal fun Iterable<String>.shellAnd(): String = joinToString(" && ") { "{ $it; }" }

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

internal data class FirewallRestoreBatch(
    val tool: String,
    val table: String,
    val commands: List<String>,
) {
    fun payload(): String {
        require(table in listOf("mangle", "filter", "nat"))
        val prefix = "$tool -t $table "
        return buildString {
            append("*$table\n")
            commands.forEach { command ->
                require(command.startsWith(prefix))
                val rule = command.removePrefix(prefix)
                require('\n' !in rule && ';' !in rule && '&' !in rule && '|' !in rule)
                append(rule).append('\n')
            }
            append("COMMIT")
        }
    }

    fun command(): String = "printf '%s\\n' ${shellQuote(payload())} | ${FirewallCommands.restore(tool)}"
}
