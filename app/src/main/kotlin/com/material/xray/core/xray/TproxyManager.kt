package com.material.xray.core.xray

import com.material.xray.core.app.appUidRangeForProfile
import com.material.xray.core.app.isApplicationUid
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.FirewallCommands.IPV4
import com.material.xray.core.xray.FirewallCommands.IPV6

data class TproxyTrafficGroup(
    val state: TproxyGroupState,
    val uids: Set<Int>,
    val isBase: Boolean = false,
)

data class TproxyTrafficPlan(
    val runtimeState: TproxyRuntimeState,
    val groups: List<TproxyTrafficGroup>,
    val bypassUids: Set<Int>,
    val routeProfileIds: Set<Int>,
)

class TproxyManager internal constructor(
    private val appUid: Int,
    private val executeCommand: suspend (String) -> RootShell.Result,
) {
    constructor(shell: RootShell, appUid: Int) : this(appUid, { command -> shell.execute(command) })

    private var bulkRestoreSupported = false
    private var useIndividualCommands = false
    private var guardCoversTethering = false
    private val localAddressTracker = LocalAddressChangeTracker()

    internal suspend fun readLocalAddresses(includeIpv6: Boolean): List<String> = LocalAddresses.read(includeIpv6, executeCommand)

    suspend fun localAddressesChanged(): Boolean = localAddressTracker.hasStableChange(
        readLocalAddresses(localAddressTracker.includeIpv6),
    )

    suspend fun installGuard(plan: TproxyTrafficPlan): TunManager.RoutingResult {
        if (useIndividualCommands) return installGuardIndividually(plan, hasCompleteGuard(plan))
        val restored = executeCommand(guardRestoreCommand(plan, appUid, checkSupport = !bulkRestoreSupported))
        if (restored.isSuccess) {
            bulkRestoreSupported = true
            guardCoversTethering = plan.runtimeState.tetherUpstreamInterface != null
            return TunManager.RoutingResult(success = true)
        }
        if (restored.canRetryIndividually()) {
            val existingGuard = hasCompleteGuard(plan)
            useIndividualCommands = true
            return installGuardIndividually(plan, existingGuard).withRestoreError(restored)
        }
        return restored.toRoutingResult("TPROXY bulk startup guard setup")
    }

    suspend fun activate(plan: TproxyTrafficPlan): TunManager.RoutingResult {
        val state = plan.runtimeState
        localAddressTracker.markInstalled(state.localAddresses, state.ipv6Enabled)
        val inspection = executeCommand(activationInspectionCommand(state))
        if (!inspection.isSuccess) return inspection.toRoutingResult("TPROXY namespace inspection")
        val sections = inspection.output.split(ACTIVATION_INSPECTION_SEPARATOR, limit = 2)
        if (sections.size != 2) {
            return TunManager.RoutingResult(success = false, error = "TPROXY namespace inspection returned invalid output")
        }
        if (
            overlappingFwmarkRules(sections[0], state.markPrefix, state.markMask).any {
                it.priority <= state.rulePriority
            }
        ) {
            return TunManager.RoutingResult(success = false, error = "TPROXY packet-mark namespace conflicts with an existing rule")
        }
        if (sections[1].isNotBlank()) {
            return TunManager.RoutingResult(success = false, error = "TPROXY route table ${state.routeTable} is already in use")
        }
        if (useIndividualCommands) return execute(activationCommand(plan, appUid), "TPROXY routing setup")
        val restored = executeCommand(activationRestoreCommand(plan, appUid, checkSupport = !bulkRestoreSupported))
        if (restored.isSuccess) return TunManager.RoutingResult(success = true)
        if (!restored.canRetryIndividually()) {
            return restored.toRoutingResult("TPROXY bulk routing setup")
        }
        val cleanup = executeCommand(cleanupCommand(state, appUid, preserveGuard = true))
        if (!cleanup.isSuccess) return cleanup.toRoutingResult("TPROXY routing rollback")
        useIndividualCommands = true
        return activate(plan).withRestoreError(restored)
    }

    private suspend fun hasCompleteGuard(plan: TproxyTrafficPlan): Boolean = executeCommand(guardHookVerifyCommand(plan, appUid)).isSuccess

    private suspend fun installGuardIndividually(
        plan: TproxyTrafficPlan,
        existingGuard: Boolean,
    ): TunManager.RoutingResult {
        if (existingGuard) {
            val verified = executeCommand(guardPlanVerifyCommand(plan, appUid))
            if (verified.isSuccess) {
                guardCoversTethering = plan.runtimeState.tetherUpstreamInterface != null
                return TunManager.RoutingResult(success = true)
            }
            return TunManager.RoutingResult(
                success = false,
                error = "TPROXY startup guard changed and cannot be refreshed safely without iptables-restore",
            )
        }
        val cleanup = executeCommand(guardCleanupCommand(appUid))
        if (!cleanup.isSuccess) return cleanup.toRoutingResult("TPROXY startup guard rollback")
        val result = execute(guardInstallCommand(plan, appUid), "TPROXY startup guard setup")
        if (result.success) {
            guardCoversTethering = plan.runtimeState.tetherUpstreamInterface != null
        } else {
            executeCommand(guardCleanupCommand(appUid))
        }
        return result
    }

    // An extension warning from restore does not prove standalone iptables will fail too.
    // Retry only after rollback; a genuinely missing extension still fails closed.
    private fun RootShell.Result.canRetryIndividually(): Boolean = exitCode == COMMAND_NOT_FOUND_EXIT_CODE ||
        (exitCode > 0 && "Extension " in error && "not supported, missing kernel module?" in error)

    private fun TunManager.RoutingResult.withRestoreError(restored: RootShell.Result): TunManager.RoutingResult = if (success) this else copy(error = "$error; restore failure: ${restored.output} ${restored.error}".trim())

    suspend fun update(plan: TproxyTrafficPlan, currentSlot: String): TunManager.RoutingResult {
        val nextSlot = if (currentSlot == SLOT_A) SLOT_B else SLOT_A
        return execute(updateCommand(plan, appUid, currentSlot, nextSlot), "TPROXY app routing update")
    }

    suspend fun verify(state: TproxyRuntimeState): TunManager.RoutingResult {
        localAddressTracker.ensureInstalled(state.localAddresses, state.ipv6Enabled)
        if (state.tetherUpstreamInterface != null && state.localAddresses.isEmpty()) {
            return TunManager.RoutingResult(false, "Local interface addresses were not captured during routing setup")
        }
        return execute(verifyCommand(state, appUid), "TPROXY routing verification")
    }

    suspend fun remove(state: TproxyRuntimeState?, preserveGuard: Boolean = false): Boolean = executeCommand(cleanupCommand(state, appUid, preserveGuard)).isSuccess

    suspend fun removeGuard(): Boolean = executeCommand(guardCleanupCommand(appUid, guardCoversTethering)).isSuccess.also {
        if (it) guardCoversTethering = false
    }

    private suspend fun execute(command: String, label: String): TunManager.RoutingResult {
        val result = executeCommand(command)
        if (result.isSuccess) return TunManager.RoutingResult(success = true)
        return result.toRoutingResult(label)
    }

    private fun RootShell.Result.toRoutingResult(label: String): TunManager.RoutingResult {
        val details = listOf(output.trim(), error.trim()).filter(String::isNotEmpty).joinToString(" | ")
        return TunManager.RoutingResult(
            success = false,
            error = if (details.isEmpty()) "$label failed (exit=$exitCode)" else "$label failed: $details",
        )
    }

    internal companion object {
        const val SLOT_A = "a"
        const val SLOT_B = "b"

        // Run before Android's first fwmark rule (normally priority 10000). Group marking preserves
        // Android's low fwmark fields, so those rules must not consume intercepted packets first.
        const val RULE_PRIORITY = 9_990
        private const val ACTIVATION_INSPECTION_SEPARATOR = "__MXRAY_TPROXY_ROUTES__"
        private const val COMMAND_NOT_FOUND_EXIT_CODE = 127

        fun createRuntimeState(
            routeTable: Int,
            groups: List<Pair<Long, String>>,
            ports: List<Int>,
            allowIpv6: Boolean,
            tetherUpstreamInterface: String? = null,
            tetherBypassLan: Boolean = true,
            localAddresses: List<String> = emptyList(),
        ): TproxyRuntimeState {
            require(groups.isNotEmpty())
            require(groups.size == ports.size)
            require(routeTable in 1..32_765)
            require(ports.all { it in 1..65_535 } && ports.distinct().size == ports.size)
            require(groups.size <= TproxyCompatibilityDetector.MAX_GROUPS)
            require(tetherUpstreamInterface == null || TETHER_INTERFACE_PATTERN.matches(tetherUpstreamInterface))
            return TproxyRuntimeState(
                markPrefix = TproxyCompatibilityDetector.MARK_PREFIX,
                markMask = TproxyCompatibilityDetector.MARK_MASK,
                routeTable = routeTable,
                rulePriority = RULE_PRIORITY,
                outputChainSlot = SLOT_A,
                groups = groups.mapIndexed { index, (routeKey, inboundTag) ->
                    TproxyGroupState(
                        routeKey = routeKey,
                        mark = TproxyCompatibilityDetector.groupMark(index + 1),
                        port = ports[index],
                        inboundTag = inboundTag,
                    )
                },
                ipv6Enabled = allowIpv6,
                tetherUpstreamInterface = tetherUpstreamInterface,
                tetherBypassLan = tetherBypassLan,
                localAddresses = localAddresses,
            )
        }

        fun activationCommand(plan: TproxyTrafficPlan, appUid: Int): String {
            validatePlan(plan, appUid)
            return (routingActivationCommands(plan) + firewallActivationCommands(plan, appUid).flatMap { it.commands })
                .shellAnd()
        }

        internal fun activationRestoreCommand(plan: TproxyTrafficPlan, appUid: Int, checkSupport: Boolean = true): String {
            validatePlan(plan, appUid)
            val restores = firewallActivationCommands(plan, appUid)
            val activation = buildList {
                addAll(routingActivationCommands(plan))
                restores.forEach { restore ->
                    add(restore.command())
                }
            }.shellAnd()
            val rollback = cleanupCommand(plan.runtimeState, appUid, preserveGuard = true)
            val guardedActivation = "if $activation; then true; else status=\$?; $rollback; exit \$status; fi"
            return if (checkSupport) {
                "if ${FirewallCommands.restoreAvailable()}; then $guardedActivation; else exit 127; fi"
            } else {
                guardedActivation
            }
        }

        private fun activationInspectionCommand(state: TproxyRuntimeState): String = "ip rule show && ip -6 rule show && " +
            "printf '\\n$ACTIVATION_INSPECTION_SEPARATOR\\n' && " +
            "ip route show table ${state.routeTable} && ip -6 route show table ${state.routeTable}"

        fun guardInstallCommand(plan: TproxyTrafficPlan, appUid: Int): String {
            validatePlan(plan, appUid)
            val names = chainNames(appUid)
            val commands = buildGuardCommands(IPV4, names.guard, plan, appUid).toMutableList()
            commands += buildGuardCommands(IPV6, names.guard, plan, appUid)
            if (plan.runtimeState.tetherUpstreamInterface != null) {
                commands += buildTetherGuardCommands(IPV4, names.guard, plan)
                commands += buildTetherGuardCommands(IPV6, names.guard, plan)
            } else {
                commands += tetherGuardCleanupCommands(IPV4, names.guard)
                commands += tetherGuardCleanupCommands(IPV6, names.guard)
            }
            return commands.shellAnd()
        }

        internal fun guardRestoreCommand(
            plan: TproxyTrafficPlan,
            appUid: Int,
            checkSupport: Boolean = true,
        ): String {
            validatePlan(plan, appUid)
            val guard = chainNames(appUid).guard
            val restores = FirewallCommands.tools.flatMap { tool ->
                buildList {
                    val setup = FirewallRestoreBatch(tool, "mangle", guardSetupCommands(tool, guard, plan, appUid))
                    val refresh = FirewallRestoreBatch(tool, "mangle", guardRefreshCommands(tool, guard, plan, appUid))
                    add(
                        "if $tool -t mangle -C OUTPUT -j $guard 2>/dev/null; then ${refresh.command()}; else " +
                            "${guardCleanupCommands(tool, guard).joinToString("; ")}; ${setup.command()}; fi",
                    )
                    if (plan.runtimeState.tetherUpstreamInterface != null) {
                        val filterSetup = FirewallRestoreBatch(tool, "filter", tetherGuardSetupCommands(tool, guard, plan))
                        val filterRefresh = FirewallRestoreBatch(tool, "filter", tetherGuardRefreshCommands(tool, guard, plan))
                        add(
                            "if $tool -t filter -C INPUT -j ${guard}I 2>/dev/null && " +
                                "$tool -t filter -C FORWARD -j $guard 2>/dev/null; then ${filterRefresh.command()}; else " +
                                "${tetherGuardCleanupCommands(tool, guard).joinToString("; ")}; " +
                                "${filterSetup.command()}; fi",
                        )
                    } else {
                        add("{ ${tetherGuardCleanupCommands(tool, guard).joinToString("; ")}; }")
                    }
                }
            }
            val guardedRestore = "if ${restores.shellAnd()}; " +
                "then ${guardPlanVerifyCommand(plan, appUid)}; else status=\$?; " +
                "if ${guardHookVerifyCommand(plan, appUid)}; then true; else ${guardCleanupCommand(appUid)}; fi; " +
                "exit \$status; fi"
            return if (checkSupport) {
                "if ${FirewallCommands.restoreAvailable()}; then $guardedRestore; else exit 127; fi"
            } else {
                guardedRestore
            }
        }

        private fun routingActivationCommands(plan: TproxyTrafficPlan): List<String> {
            val state = plan.runtimeState
            val markPrefix = hex(state.markPrefix)
            val markMask = hex(state.markMask)
            return buildList {
                add("ip route replace local 0.0.0.0/0 dev lo table ${state.routeTable}")
                add("ip rule add fwmark $markPrefix/$markMask table ${state.routeTable} pref ${state.rulePriority}")
                if (state.ipv6Enabled) {
                    add("ip -6 route replace local ::/0 dev lo table ${state.routeTable}")
                    add("ip -6 rule add fwmark $markPrefix/$markMask table ${state.routeTable} pref ${state.rulePriority}")
                }
            }
        }

        private fun firewallActivationCommands(plan: TproxyTrafficPlan, appUid: Int): List<FirewallRestoreBatch> {
            val state = plan.runtimeState
            val names = chainNames(appUid)
            val ipv4 = buildPreroutingCommands(IPV4, names.prerouting, plan) +
                buildOutputActivationCommands(IPV4, names, plan, appUid, SLOT_A)
            val ipv6 = if (state.ipv6Enabled) {
                FirewallRestoreBatch(
                    tool = IPV6,
                    table = "mangle",
                    commands = buildPreroutingCommands(IPV6, names.prerouting, plan) +
                        buildOutputActivationCommands(IPV6, names, plan, appUid, SLOT_A),
                )
            } else {
                FirewallRestoreBatch(
                    tool = IPV6,
                    table = "filter",
                    commands = buildIpv6RejectActivationCommands(names, plan, appUid, SLOT_A),
                )
            }
            return listOf(FirewallRestoreBatch(IPV4, "mangle", ipv4), ipv6)
        }

        fun updateCommand(
            plan: TproxyTrafficPlan,
            appUid: Int,
            currentSlot: String,
            nextSlot: String,
        ): String {
            validatePlan(plan, appUid)
            require(currentSlot in setOf(SLOT_A, SLOT_B) && nextSlot in setOf(SLOT_A, SLOT_B) && currentSlot != nextSlot)
            val names = chainNames(appUid)
            val commands = buildOutputUpdateCommands(IPV4, names, plan, appUid, currentSlot, nextSlot).toMutableList()
            if (plan.runtimeState.ipv6Enabled) {
                commands += buildOutputUpdateCommands(IPV6, names, plan, appUid, currentSlot, nextSlot)
            } else {
                commands += buildIpv6RejectUpdateCommands(names, plan, appUid, currentSlot, nextSlot)
            }
            return commands.shellAnd()
        }

        fun verifyCommand(state: TproxyRuntimeState, appUid: Int): String {
            val names = chainNames(appUid)
            val prefix = hex(state.markPrefix)
            val mask = hex(state.markMask)
            val groupMask = hex(TproxyCompatibilityDetector.GROUP_MARK_MASK)
            val baseMark = hex(state.groups.first().mark)
            val clearXrayMarkRule =
                "${names.slot(state.outputChainSlot)} -m owner --gid-owner $appUid " +
                    "-m mark --mark $prefix/$mask -j MARK --set-xmark 0x0/$groupMask"
            val returnXrayRule = "${names.slot(state.outputChainSlot)} -m owner --gid-owner $appUid -j RETURN"
            fun hasV4(rule: String) = "has_v4 ${shellQuote("-A $rule")}"
            fun hasV6(rule: String) = "has_v6 ${shellQuote("-A $rule")}"
            val commands = mutableListOf(
                "v4_rules=\$($IPV4 -t mangle -S)",
                "v4_slot_rules=\$($IPV4 -t mangle -S ${names.slot(state.outputChainSlot)})",
                "newline='\n'",
                "has_v4() { case \"\$newline\$v4_rules\$newline\" in " +
                    "*\"\$newline\$1\$newline\"*) true;; *) " +
                    "{ printf 'missing v4 rule: %s\\n' \"\$1\" >&2; " +
                    "chain=\${1#-A }; chain=\${chain%% *}; printf '%s\\n' \"\$v4_rules\" | " +
                    "grep -F -- \"-A \$chain \" >&2; return 1; };; esac; }",
                "has_v4_fragment() { case \"\$v4_slot_rules\" in *\"\$1\"*) true;; *) return 1;; esac; }",
                "has_v4_order() { case \"\$newline\$v4_slot_rules\$newline\" in " +
                    "*\"\$newline\$1\$newline\$2\$newline\"*) true;; *) return 1;; esac; }",
                "has_port() { case \"\$1\" in *\":\$2 \"*|*\".\$2 \"*) true;; *) return 1;; esac; }",
                "listeners=\$(ss -lntu)",
                "tcp_listeners=\$(printf '%s\\n' \"\$listeners\" | grep '^tcp ')",
                "udp_listeners=\$(printf '%s\\n' \"\$listeners\" | grep '^udp ')",
                hasV4("OUTPUT -j ${names.output}"),
                hasV4("${names.output} -j ${names.slot(state.outputChainSlot)}"),
                hasV4("PREROUTING -j ${names.prerouting}"),
                hasV4("INPUT -j ${names.prerouting}L"),
                "ip rule show | grep -q 'fwmark $prefix/$mask.*lookup ${state.routeTable}'",
                "ip route show table ${state.routeTable} | grep -q '^local .* dev lo'",
                hasV4(clearXrayMarkRule),
                hasV4(returnXrayRule),
                "has_v4_order ${shellQuote("-A $clearXrayMarkRule")} ${shellQuote("-A $returnXrayRule")}",
            )
            for (protocol in listOf("tcp", "udp")) {
                commands += hasV4(
                    "${names.slot(state.outputChainSlot)} -p $protocol -m $protocol --dport 53 " +
                        "-j MARK --set-xmark $baseMark/$groupMask",
                )
            }
            state.groups.forEach { group ->
                val mark = hex(group.mark)
                commands += "has_v4_fragment ${shellQuote("--set-xmark $mark/$groupMask")}"
                for (protocol in listOf("tcp", "udp")) {
                    commands += hasV4("${names.prerouting}L ${listenerDestinationMatch(state)}-p $protocol -m $protocol --dport ${group.port} -m mark ! --mark $prefix/$mask -j DROP")
                    commands += hasV4(
                        "${names.prerouting} -p $protocol -m mark --mark $mark/$groupMask -j TPROXY --on-port ${group.port} --on-ip ${
                            tproxyOnIp(IPV4, state.ipv6Enabled, state.tetherUpstreamInterface != null)
                        } --tproxy-mark $mark/$groupMask",
                    )
                    commands += hasV4(
                        "${names.slot(state.outputChainSlot)} " +
                            "${canonicalLocalDestinationMatch(state, protocol)} -m $protocol --dport ${group.port} -j DROP",
                    )
                }
                commands += "has_port \"\$tcp_listeners\" ${group.port}"
                commands += "has_port \"\$udp_listeners\" ${group.port}"
            }
            if (state.ipv6Enabled) {
                commands += "v6_rules=\$($IPV6 -t mangle -S)"
                commands += "v6_slot_rules=\$($IPV6 -t mangle -S ${names.slot(state.outputChainSlot)})"
                commands += "has_v6() { case \"\$newline\$v6_rules\$newline\" in " +
                    "*\"\$newline\$1\$newline\"*) true;; *) return 1;; esac; }"
                commands += "has_v6_fragment() { case \"\$v6_slot_rules\" in *\"\$1\"*) true;; *) return 1;; esac; }"
                commands += "has_v6_order() { case \"\$newline\$v6_slot_rules\$newline\" in " +
                    "*\"\$newline\$1\$newline\$2\$newline\"*) true;; *) return 1;; esac; }"
                commands += hasV6("OUTPUT -j ${names.output}")
                commands += hasV6("${names.output} -j ${names.slot(state.outputChainSlot)}")
                commands += hasV6("PREROUTING -j ${names.prerouting}")
                commands += hasV6("INPUT -j ${names.prerouting}L")
                commands += "ip -6 rule show | grep -q 'fwmark $prefix/$mask.*lookup ${state.routeTable}'"
                commands += "ip -6 route show table ${state.routeTable} | grep -q '^local .* dev lo'"
                commands += hasV6(clearXrayMarkRule)
                commands += hasV6(returnXrayRule)
                commands += "has_v6_order ${shellQuote("-A $clearXrayMarkRule")} ${shellQuote("-A $returnXrayRule")}"
                for (protocol in listOf("tcp", "udp")) {
                    commands += hasV6(
                        "${names.slot(state.outputChainSlot)} -p $protocol -m $protocol --dport 53 " +
                            "-j MARK --set-xmark $baseMark/$groupMask",
                    )
                }
                state.groups.forEach { group ->
                    val mark = hex(group.mark)
                    commands += "has_v6_fragment ${shellQuote("--set-xmark $mark/$groupMask")}"
                    for (protocol in listOf("tcp", "udp")) {
                        commands += hasV6("${names.prerouting}L ${listenerDestinationMatch(state)}-p $protocol -m $protocol --dport ${group.port} -m mark ! --mark $prefix/$mask -j DROP")
                        commands += hasV6(
                            "${names.prerouting} -p $protocol -m mark --mark $mark/$groupMask -j TPROXY " +
                                "--on-port ${group.port} --on-ip ${tproxyOnIp(IPV6, state.ipv6Enabled)} " +
                                "--tproxy-mark $mark/$groupMask",
                        )
                        commands += hasV6(
                            "${names.slot(state.outputChainSlot)} " +
                                "${canonicalLocalDestinationMatch(state, protocol)} " +
                                "-m $protocol --dport ${group.port} -j DROP",
                        )
                    }
                }
            } else {
                commands += "v6_rules=\$($IPV6 -t filter -S)"
                commands += "v6_slot_rules=\$($IPV6 -t filter -S ${names.slot(state.outputChainSlot)})"
                commands += "has_v6() { case \"\$newline\$v6_rules\$newline\" in " +
                    "*\"\$newline\$1\$newline\"*) true;; *) return 1;; esac; }"
                commands += hasV6("OUTPUT -j ${names.output}")
                commands += hasV6("${names.output} -j ${names.slot(state.outputChainSlot)}")
                commands += "case \"\$v6_slot_rules\" in *'--reject-with icmp6-no-route'*) true;; *) false;; esac"
            }
            state.tetherUpstreamInterface?.let { upstream ->
                val basePort = state.groups.first().port
                commands += hasV4("${names.prerouting} -i $upstream -j RETURN")
                for (protocol in listOf("tcp", "udp")) {
                    commands += hasV4(
                        "${names.prerouting} -p $protocol -m $protocol --dport 53 -j TPROXY --on-port $basePort " +
                            "--on-ip 0.0.0.0 --tproxy-mark $baseMark/$groupMask",
                    )
                    commands += hasV4(
                        "${names.prerouting} -p $protocol -j TPROXY --on-port $basePort --on-ip 0.0.0.0 " +
                            "--tproxy-mark $baseMark/$groupMask",
                    )
                }
                if (!state.ipv6Enabled) {
                    commands += hasV6("INPUT -j ${names.prerouting}I")
                    commands += hasV6("FORWARD -j ${names.prerouting}")
                }
            }
            return commands.shellAnd()
        }

        fun cleanupCommand(state: TproxyRuntimeState?, appUid: Int, preserveGuard: Boolean = false): String {
            require(appUid > 0)
            val names = chainNames(appUid)
            val table = state?.routeTable
            val priority = state?.rulePriority ?: RULE_PRIORITY
            val prefix = hex(state?.markPrefix ?: TproxyCompatibilityDetector.MARK_PREFIX)
            val mask = hex(state?.markMask ?: TproxyCompatibilityDetector.MARK_MASK)
            val commands = mutableListOf<String>()
            for (tool in FirewallCommands.tools) {
                if (!preserveGuard) {
                    commands += "$tool -t mangle -D OUTPUT -j ${names.guard} 2>/dev/null || true"
                    commands += "$tool -t filter -D INPUT -j ${names.guard} 2>/dev/null || true"
                    commands += "$tool -t filter -D INPUT -j ${names.guard}I 2>/dev/null || true"
                    commands += "$tool -t filter -F ${names.guard}I 2>/dev/null || true"
                    commands += "$tool -t filter -X ${names.guard}I 2>/dev/null || true"
                    commands += "$tool -t filter -D FORWARD -j ${names.guard} 2>/dev/null || true"
                    commands += "$tool -t filter -F ${names.guard} 2>/dev/null || true"
                    commands += "$tool -t filter -X ${names.guard} 2>/dev/null || true"
                }
                commands += "$tool -t mangle -D OUTPUT -j ${names.output} 2>/dev/null || true"
                commands += "$tool -t mangle -D PREROUTING -j ${names.prerouting} 2>/dev/null || true"
                commands += "$tool -t mangle -D INPUT -j ${names.prerouting}L 2>/dev/null || true"
                commands += "$tool -t filter -D INPUT -j ${names.prerouting} 2>/dev/null || true"
                commands += "$tool -t filter -D INPUT -j ${names.prerouting}I 2>/dev/null || true"
                commands += "$tool -t filter -F ${names.prerouting}I 2>/dev/null || true"
                commands += "$tool -t filter -X ${names.prerouting}I 2>/dev/null || true"
                commands += "$tool -t filter -D FORWARD -j ${names.prerouting} 2>/dev/null || true"
                val chains = listOf(names.output, names.slotA, names.slotB, names.prerouting, names.prerouting + "L") +
                    names.guard.takeUnless { preserveGuard }
                for (chain in chains.filterNotNull()) {
                    commands += "$tool -t mangle -F $chain 2>/dev/null || true"
                    commands += "$tool -t mangle -X $chain 2>/dev/null || true"
                }
                commands += "$tool -t filter -F ${names.prerouting} 2>/dev/null || true"
                commands += "$tool -t filter -X ${names.prerouting} 2>/dev/null || true"
            }
            commands += "$IPV6 -t filter -D OUTPUT -j ${names.output} 2>/dev/null || true"
            for (chain in listOf(names.output, names.slotA, names.slotB)) {
                commands += "$IPV6 -t filter -F $chain 2>/dev/null || true"
                commands += "$IPV6 -t filter -X $chain 2>/dev/null || true"
            }
            commands += discoveredRouteTableCleanupCommand("ip", prefix, mask, priority)
            commands += discoveredRouteTableCleanupCommand("ip -6", prefix, mask, priority)
            commands += "while ip rule del fwmark $prefix/$mask pref $priority 2>/dev/null; do :; done"
            commands += "while ip -6 rule del fwmark $prefix/$mask pref $priority 2>/dev/null; do :; done"
            table?.let {
                commands += "ip route del local 0.0.0.0/0 dev lo table $it 2>/dev/null || true"
                commands += "ip -6 route del local ::/0 dev lo table $it 2>/dev/null || true"
                commands += "ip -6 route del unreachable default table $it 2>/dev/null || true"
            }
            val ownedChains = listOf(names.output, names.slotA, names.slotB, names.prerouting, names.prerouting + "I", names.prerouting + "L") +
                listOf(names.guard, names.guard + "I").takeUnless { preserveGuard }.orEmpty()
            commands += FirewallCommands.absentChains(ownedChains)
            return commands.joinToString("; ")
        }

        fun guardCleanupCommand(appUid: Int, includeFilterTables: Boolean = true): String {
            require(appUid > 0)
            val guard = chainNames(appUid).guard
            val tools = FirewallCommands.tools
            val commands = tools.flatMap { tool ->
                buildList {
                    add("$tool -t mangle -D OUTPUT -j $guard 2>/dev/null || true")
                    add("$tool -t mangle -F $guard 2>/dev/null || true")
                    add("$tool -t mangle -X $guard 2>/dev/null || true")
                    if (includeFilterTables) {
                        add("$tool -t filter -D INPUT -j $guard 2>/dev/null || true")
                        add("$tool -t filter -D INPUT -j ${guard}I 2>/dev/null || true")
                        add("$tool -t filter -F ${guard}I 2>/dev/null || true")
                        add("$tool -t filter -X ${guard}I 2>/dev/null || true")
                        add("$tool -t filter -D FORWARD -j $guard 2>/dev/null || true")
                        add("$tool -t filter -F $guard 2>/dev/null || true")
                        add("$tool -t filter -X $guard 2>/dev/null || true")
                    }
                }
            }.toMutableList()
            commands += FirewallCommands.absentChains(listOf(guard, guard + "I"))
            return commands.joinToString("; ")
        }

        fun guardHookVerifyCommand(plan: TproxyTrafficPlan, appUid: Int): String {
            validatePlan(plan, appUid)
            require(appUid > 0)
            val guard = chainNames(appUid).guard
            return buildList {
                for (tool in FirewallCommands.tools) {
                    add(firstHookIs(tool, "mangle", "OUTPUT", guard))
                    if (plan.runtimeState.tetherUpstreamInterface != null) {
                        add(firstHookIs(tool, "filter", "INPUT", "${guard}I"))
                        add(firstHookIs(tool, "filter", "FORWARD", guard))
                    }
                }
            }.shellAnd()
        }

        fun guardPlanVerifyCommand(plan: TproxyTrafficPlan, appUid: Int): String {
            validatePlan(plan, appUid)
            val guard = chainNames(appUid).guard
            return buildList {
                for (tool in FirewallCommands.tools) {
                    add(firstHookIs(tool, "mangle", "OUTPUT", guard))
                    add(exactChain(tool, "mangle", guard, guardRuleCommands(tool, guard, plan, appUid)))
                    if (plan.runtimeState.tetherUpstreamInterface != null) {
                        add(firstHookIs(tool, "filter", "INPUT", "${guard}I"))
                        add(firstHookIs(tool, "filter", "FORWARD", guard))
                        add(exactChain(tool, "filter", guard, tetherForwardRuleCommands(tool, guard, plan)))
                        add(exactChain(tool, "filter", "${guard}I", tetherInputRuleCommands(tool, "${guard}I", plan)))
                    } else {
                        add("! $tool -t filter -C INPUT -j ${guard}I 2>/dev/null")
                        add("! $tool -t filter -C FORWARD -j $guard 2>/dev/null")
                    }
                }
            }.shellAnd()
        }

        private fun discoveredRouteTableCleanupCommand(
            ipCommand: String,
            prefix: String,
            mask: String,
            priority: Int,
        ): String = "tables=\$($ipCommand rule show 2>/dev/null | while IFS= read -r line; do " +
            "case \"\$line\" in \"$priority:\"*\"fwmark $prefix/$mask\"*) " +
            "previous=''; for field in \$line; do " +
            "[ \"\$previous\" = lookup ] && printf '%s\\n' \"\$field\"; previous=\$field; done;; esac; done); " +
            "for table in \$tables; do case \"\$table\" in ''|*[!0-9]*) continue;; esac; " +
            "$ipCommand route del local ${if (ipCommand == "ip -6") "::/0" else "0.0.0.0/0"} " +
            "dev lo table \"\$table\" 2>/dev/null || true; done"

        private fun buildGuardCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> {
            val setup = guardSetupCommands(tool, chain, plan, appUid).shellAnd()
            return listOf("if $tool -t mangle -C OUTPUT -j $chain 2>/dev/null; then true; else $setup; fi")
        }

        private fun buildTetherGuardCommands(tool: String, chain: String, plan: TproxyTrafficPlan): List<String> {
            val setup = tetherGuardSetupCommands(tool, chain, plan).shellAnd()
            val cleanup = tetherGuardCleanupCommands(tool, chain).joinToString("; ")
            return listOf(
                "if $tool -t filter -C INPUT -j ${chain}I 2>/dev/null && " +
                    "$tool -t filter -C FORWARD -j $chain 2>/dev/null; then true; else $cleanup; $setup; fi",
            )
        }

        private fun tetherGuardCleanupCommands(tool: String, chain: String): List<String> = listOf(
            "$tool -t filter -D INPUT -j $chain 2>/dev/null || true",
            "$tool -t filter -D INPUT -j ${chain}I 2>/dev/null || true",
            "$tool -t filter -F ${chain}I 2>/dev/null || true",
            "$tool -t filter -X ${chain}I 2>/dev/null || true",
            "$tool -t filter -D FORWARD -j $chain 2>/dev/null || true",
            "$tool -t filter -F $chain 2>/dev/null || true",
            "$tool -t filter -X $chain 2>/dev/null || true",
        )

        private fun tetherGuardSetupCommands(tool: String, chain: String, plan: TproxyTrafficPlan): List<String> = buildList {
            add("$tool -t filter -N $chain")
            addAll(tetherForwardRuleCommands(tool, chain, plan))
            add("$tool -t filter -N ${chain}I")
            addAll(tetherInputRuleCommands(tool, "${chain}I", plan))
            add("$tool -t filter -I INPUT 1 -j ${chain}I")
            add("$tool -t filter -I FORWARD 1 -j $chain")
        }

        private fun tetherGuardRefreshCommands(tool: String, chain: String, plan: TproxyTrafficPlan): List<String> = buildList {
            add("$tool -t filter -D INPUT -j ${chain}I")
            add("$tool -t filter -D FORWARD -j $chain")
            add("$tool -t filter -F $chain")
            addAll(tetherForwardRuleCommands(tool, chain, plan))
            add("$tool -t filter -F ${chain}I")
            addAll(tetherInputRuleCommands(tool, "${chain}I", plan))
            add("$tool -t filter -I INPUT 1 -j ${chain}I")
            add("$tool -t filter -I FORWARD 1 -j $chain")
        }

        private fun tetherForwardRuleCommands(tool: String, chain: String, plan: TproxyTrafficPlan): List<String> = buildList {
            val upstream = requireNotNull(plan.runtimeState.tetherUpstreamInterface)
            add("$tool -t filter -A $chain -i $upstream -j RETURN")
            for (protocol in listOf("tcp", "udp")) {
                add("$tool -t filter -A $chain -p $protocol -m $protocol --dport 53 -j DROP")
            }
            tetherBypassCidrs(tool, plan.runtimeState.tetherBypassLan).forEach { cidr ->
                add("$tool -t filter -A $chain -d $cidr -j RETURN")
            }
            add("$tool -t filter -A $chain -j DROP")
        }

        private fun tetherInputRuleCommands(tool: String, chain: String, plan: TproxyTrafficPlan): List<String> = tetherInputRuleCommands(
            tool = tool,
            chain = chain,
            upstream = requireNotNull(plan.runtimeState.tetherUpstreamInterface),
            target = "DROP",
            guardedState = plan.runtimeState,
        )

        private fun tetherInputRules(
            tool: String,
            chain: String,
            upstream: String,
            target: String,
            guardedState: TproxyRuntimeState? = null,
        ): List<String> = buildList {
            add("$tool -t filter -N $chain")
            addAll(tetherInputRuleCommands(tool, chain, upstream, target, guardedState))
        }

        private fun tetherInputRuleCommands(
            tool: String,
            chain: String,
            upstream: String,
            target: String,
            guardedState: TproxyRuntimeState? = null,
        ): List<String> = buildList {
            guardedState?.let { state ->
                add("$tool -t filter -A $chain -m mark --mark ${hex(state.markPrefix)}/${hex(state.markMask)} -j DROP")
            }
            add("$tool -t filter -A $chain -i $upstream -j RETURN")
            for (protocol in listOf("tcp", "udp")) {
                add("$tool -t filter -A $chain -p $protocol -m $protocol --dport 53 -j $target")
            }
        }

        private fun guardSetupCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            add("$tool -t mangle -N $chain")
            addAll(guardRuleCommands(tool, chain, plan, appUid))
            add("$tool -t mangle -I OUTPUT 1 -j $chain")
        }

        private fun guardRefreshCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            add("$tool -t mangle -D OUTPUT -j $chain")
            add("$tool -t mangle -F $chain")
            addAll(guardRuleCommands(tool, chain, plan, appUid))
            add("$tool -t mangle -I OUTPUT 1 -j $chain")
        }

        private fun guardRuleCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            val state = plan.runtimeState
            add(
                "$tool -t mangle -A $chain -m owner --gid-owner $appUid " +
                    "-m mark --mark ${hex(state.markPrefix)}/${hex(state.markMask)} " +
                    "-j MARK --set-xmark 0x0/${hex(TproxyCompatibilityDetector.GROUP_MARK_MASK)}",
            )
            add("$tool -t mangle -A $chain -m owner --gid-owner $appUid -j RETURN")
            add("$tool -t mangle -A $chain -m owner --uid-owner $appUid -j RETURN")
            uidRanges(plan.bypassUids - appUid).forEach { range ->
                add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j RETURN")
            }
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                val range = appUidRangeForProfile(profileId)
                add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j DROP")
            }
        }

        private fun firstHookIs(tool: String, table: String, hook: String, target: String): String = "[ \"\$($tool -t $table -S $hook | grep '^-A $hook ' | head -n 1)\" = " +
            "${shellQuote("-A $hook -j $target")} ]"

        private fun exactChain(
            tool: String,
            table: String,
            chain: String,
            ruleCommands: List<String>,
        ): String {
            val prefix = "$tool -t $table "
            val expected = buildList {
                add("-N $chain")
                ruleCommands.forEach { command ->
                    require(command.startsWith(prefix))
                    add(command.removePrefix(prefix))
                }
            }.joinToString("\n")
            return "actual=\$($tool -t $table -S $chain) && [ \"\$actual\" = ${shellQuote(expected)} ]"
        }

        private fun guardCleanupCommands(tool: String, chain: String): List<String> = listOf(
            "$tool -t mangle -D OUTPUT -j $chain 2>/dev/null || true",
            "$tool -t mangle -F $chain 2>/dev/null || true",
            "$tool -t mangle -X $chain 2>/dev/null || true",
            "$tool -t filter -D INPUT -j $chain 2>/dev/null || true",
            "$tool -t filter -D INPUT -j ${chain}I 2>/dev/null || true",
            "$tool -t filter -F ${chain}I 2>/dev/null || true",
            "$tool -t filter -X ${chain}I 2>/dev/null || true",
            "$tool -t filter -D FORWARD -j $chain 2>/dev/null || true",
            "$tool -t filter -F $chain 2>/dev/null || true",
            "$tool -t filter -X $chain 2>/dev/null || true",
        )

        private fun buildPreroutingCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
        ): List<String> = buildList {
            val groupMask = hex(TproxyCompatibilityDetector.GROUP_MARK_MASK)
            add("$tool -t mangle -N $chain")
            plan.groups.forEach { group ->
                val mark = hex(group.state.mark)
                val onIp = tproxyOnIp(
                    tool,
                    plan.runtimeState.ipv6Enabled,
                    acceptNonLoopback = plan.runtimeState.tetherUpstreamInterface != null,
                )
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $chain -p $protocol -m mark --mark $mark/$groupMask " +
                            "-j TPROXY --on-ip $onIp --on-port ${group.state.port} " +
                            "--tproxy-mark $mark/$groupMask",
                    )
                }
            }
            plan.runtimeState.tetherUpstreamInterface?.let { upstreamInterface ->
                val upstream = upstreamInterface
                val base = plan.groups.single { it.isBase }
                val mark = hex(base.state.mark)
                val onIp = tproxyOnIp(tool, plan.runtimeState.ipv6Enabled, acceptNonLoopback = true)
                val localAddresses = LocalAddresses.forTool(plan.runtimeState.localAddresses, tool)
                add("$tool -t mangle -A $chain -i lo -j RETURN")
                add("$tool -t mangle -A $chain -i $upstream -j RETURN")
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $chain -p $protocol --dport 53 -j TPROXY --on-ip $onIp " +
                            "--on-port ${base.state.port} --tproxy-mark $mark/$groupMask",
                    )
                }
                localAddresses.forEach { address ->
                    add("$tool -t mangle -A $chain -d $address -j RETURN")
                }
                tetherBypassCidrs(tool, plan.runtimeState.tetherBypassLan).forEach { cidr ->
                    add("$tool -t mangle -A $chain -d $cidr -j RETURN")
                }
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $chain -p $protocol -j TPROXY --on-ip $onIp " +
                            "--on-port ${base.state.port} --tproxy-mark $mark/$groupMask",
                    )
                }
                add("$tool -t mangle -A $chain -j DROP")
            }
            // A direct connection to a wildcard listener must not depend on an address snapshot.
            // Intercepted traffic carries our mark and retains its original destination port.
            val listenerChain = chain + "L"
            add("$tool -t mangle -N $listenerChain")
            plan.groups.forEach { group ->
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $listenerChain ${listenerDestinationMatch(plan.runtimeState)}-p $protocol --dport ${group.state.port} " +
                            "-m mark ! --mark ${hex(plan.runtimeState.markPrefix)}/${hex(plan.runtimeState.markMask)} -j DROP",
                    )
                }
            }
            add("$tool -t mangle -I INPUT 1 -j $listenerChain")
            add("$tool -t mangle -I PREROUTING 1 -j $chain")
        }

        private fun buildOutputActivationCommands(
            tool: String,
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            slot: String,
        ): List<String> {
            val slotChain = names.slot(slot)
            return buildList {
                add("$tool -t mangle -N $slotChain")
                addAll(outputRules(tool, slotChain, plan, appUid))
                add("$tool -t mangle -N ${names.output}")
                add("$tool -t mangle -A ${names.output} -j $slotChain")
                // The startup guard owns position 1 until listeners and routing verify successfully.
                add("$tool -t mangle -I OUTPUT 2 -j ${names.output}")
            }
        }

        private fun buildOutputUpdateCommands(
            tool: String,
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            currentSlot: String,
            nextSlot: String,
        ): List<String> {
            val currentChain = names.slot(currentSlot)
            val nextChain = names.slot(nextSlot)
            return buildList {
                add("$tool -t mangle -F $nextChain 2>/dev/null || true")
                add("$tool -t mangle -X $nextChain 2>/dev/null || true")
                add("$tool -t mangle -N $nextChain")
                addAll(outputRules(tool, nextChain, plan, appUid))
                add("$tool -t mangle -R ${names.output} 1 -j $nextChain")
                add("$tool -t mangle -F $currentChain")
                add("$tool -t mangle -X $currentChain")
            }
        }

        private fun buildIpv6RejectActivationCommands(
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            slot: String,
        ): List<String> {
            val slotChain = names.slot(slot)
            return buildList {
                add("$IPV6 -t filter -N $slotChain")
                addAll(ipv6RejectRules(slotChain, plan, appUid))
                add("$IPV6 -t filter -N ${names.output}")
                add("$IPV6 -t filter -A ${names.output} -j $slotChain")
                add("$IPV6 -t filter -I OUTPUT 1 -j ${names.output}")
                if (plan.runtimeState.tetherUpstreamInterface != null) {
                    add("$IPV6 -t filter -N ${names.prerouting}")
                    addAll(ipv6TetherRejectRules(names.prerouting, plan))
                    addAll(tetherInputRules(IPV6, names.prerouting + "I", requireNotNull(plan.runtimeState.tetherUpstreamInterface), "REJECT --reject-with icmp6-no-route"))
                    add("$IPV6 -t filter -I INPUT 1 -j ${names.prerouting}I")
                    add("$IPV6 -t filter -I FORWARD 1 -j ${names.prerouting}")
                }
            }
        }

        private fun buildIpv6RejectUpdateCommands(
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            currentSlot: String,
            nextSlot: String,
        ): List<String> {
            val currentChain = names.slot(currentSlot)
            val nextChain = names.slot(nextSlot)
            return buildList {
                add("$IPV6 -t filter -F $nextChain 2>/dev/null || true")
                add("$IPV6 -t filter -X $nextChain 2>/dev/null || true")
                add("$IPV6 -t filter -N $nextChain")
                addAll(ipv6RejectRules(nextChain, plan, appUid))
                add("$IPV6 -t filter -R ${names.output} 1 -j $nextChain")
                add("$IPV6 -t filter -F $currentChain")
                add("$IPV6 -t filter -X $currentChain")
            }
        }

        private fun ipv6RejectRules(
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            add("$IPV6 -t filter -A $chain -m owner --uid-owner $appUid -j RETURN")
            uidRanges(plan.bypassUids - appUid).forEach { range ->
                add("$IPV6 -t filter -A $chain -m owner --uid-owner ${range.asArgument()} -j RETURN")
            }
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                add(
                    "$IPV6 -t filter -A $chain -m owner --uid-owner ${appUidRangeForProfile(profileId).asArgument()} " +
                        "-j REJECT --reject-with icmp6-no-route",
                )
            }
        }

        private fun ipv6TetherRejectRules(chain: String, plan: TproxyTrafficPlan): List<String> = buildList {
            val upstream = requireNotNull(plan.runtimeState.tetherUpstreamInterface)
            add("$IPV6 -t filter -A $chain -i $upstream -j RETURN")
            for (protocol in listOf("tcp", "udp")) {
                add("$IPV6 -t filter -A $chain -p $protocol --dport 53 -j REJECT --reject-with icmp6-no-route")
            }

            tetherBypassCidrs(IPV6, plan.runtimeState.tetherBypassLan).forEach { cidr ->
                add("$IPV6 -t filter -A $chain -d $cidr -j RETURN")
            }
            add("$IPV6 -t filter -A $chain -j REJECT --reject-with icmp6-no-route")
        }

        private fun outputRules(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            val state = plan.runtimeState
            val prefix = hex(state.markPrefix)
            val mask = hex(state.markMask)
            val base = plan.groups.single { it.isBase }
            val baseMark = hex(base.state.mark)
            val groupMask = hex(TproxyCompatibilityDetector.GROUP_MARK_MASK)
            add(
                "$tool -t mangle -A $chain -m owner --gid-owner $appUid " +
                    "-m mark --mark $prefix/$mask -j MARK --set-xmark 0x0/$groupMask",
            )
            add("$tool -t mangle -A $chain -m owner --gid-owner $appUid -j RETURN")
            add("$tool -t mangle -A $chain -m owner --uid-owner $appUid -j RETURN")
            state.groups.forEach { group ->
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $chain ${
                            localDestinationMatch(state)
                        } -p $protocol " +
                            "--dport ${group.port} -j DROP",
                    )
                }
            }
            val loopback = if (tool == IPV6) "::1/128" else "127.0.0.0/8"
            val multicast = if (tool == IPV6) "ff00::/8" else "224.0.0.0/4"
            add("$tool -t mangle -A $chain -d $loopback -j RETURN")
            add("$tool -t mangle -A $chain -d $multicast -j RETURN")
            if (tool == IPV4) add("$tool -t mangle -A $chain -d 255.255.255.255/32 -j RETURN")
            uidRanges(plan.bypassUids - appUid).forEach { range ->
                add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j RETURN")
            }
            // Android sends application DNS through a system resolver UID outside the managed app ranges.
            for (protocol in listOf("tcp", "udp")) {
                add("$tool -t mangle -A $chain -p $protocol --dport 53 -j MARK --set-xmark $baseMark/$groupMask")
            }
            plan.groups.filterNot { it.isBase }.forEach { group ->
                uidRanges(group.uids).forEach { range ->
                    addMarkRules(tool, chain, range, group.state.mark, groupMask)
                }
            }
            add("$tool -t mangle -A $chain -m mark --mark $prefix/$mask -j RETURN")
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                addMarkRules(tool, chain, appUidRangeForProfile(profileId), base.state.mark, groupMask)
            }
            add("$tool -t mangle -A $chain -m mark --mark $prefix/$mask -j RETURN")
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                val range = appUidRangeForProfile(profileId)
                add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j DROP")
            }
        }

        private fun MutableList<String>.addMarkRules(
            tool: String,
            chain: String,
            range: IntRange,
            mark: Int,
            markMask: String,
        ) {
            val markHex = hex(mark)
            for (protocol in listOf("tcp", "udp")) {
                add(
                    "$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -p $protocol " +
                        "-j MARK --set-xmark $markHex/$markMask",
                )
            }
        }

        private fun localDestinationMatch(state: TproxyRuntimeState): String = if (state.ipv6Enabled || state.tetherUpstreamInterface != null) "-o lo" else "-d 127.0.0.0/8"

        private fun canonicalLocalDestinationMatch(state: TproxyRuntimeState, protocol: String): String = "${localDestinationMatch(state)} -p $protocol"

        private fun listenerDestinationMatch(state: TproxyRuntimeState): String = if (state.ipv6Enabled || state.tetherUpstreamInterface != null) "" else "-d 127.0.0.0/8 "

        private fun tproxyOnIp(tool: String, ipv6Enabled: Boolean, acceptNonLoopback: Boolean = false): String = when {
            tool == IPV6 -> "::"
            ipv6Enabled || acceptNonLoopback -> "0.0.0.0"
            else -> "127.0.0.1"
        }

        private fun tetherBypassCidrs(tool: String, bypassLan: Boolean): List<String> = if (tool == IPV6) {
            IPV6_ALWAYS_BYPASS_CIDRS + IPV6_LAN_CIDRS.takeIf { bypassLan }.orEmpty()
        } else {
            IPV4_ALWAYS_BYPASS_CIDRS + IPV4_LAN_CIDRS.takeIf { bypassLan }.orEmpty()
        }

        private fun uidRanges(uids: Set<Int>): List<IntRange> {
            val sorted = uids.filter(::isApplicationUid).toSortedSet()
            if (sorted.isEmpty()) return emptyList()
            val ranges = mutableListOf<IntRange>()
            var start = sorted.first()
            var previous = start
            sorted.drop(1).forEach { uid ->
                if (uid == previous + 1) {
                    previous = uid
                } else {
                    ranges += start..previous
                    start = uid
                    previous = uid
                }
            }
            ranges += start..previous
            return ranges
        }

        private fun validatePlan(plan: TproxyTrafficPlan, appUid: Int) {
            require(appUid > 0)
            require(plan.groups.isNotEmpty() && plan.groups.count { it.isBase } == 1)
            require(plan.groups.map { it.state } == plan.runtimeState.groups)
            require(plan.routeProfileIds.all { it >= 0 })
            require(
                plan.runtimeState.tetherUpstreamInterface == null ||
                    TETHER_INTERFACE_PATTERN.matches(plan.runtimeState.tetherUpstreamInterface),
            )
        }

        private fun IntRange.asArgument(): String = if (first == last) first.toString() else "$first-$last"

        private fun hex(value: Int): String = "0x${value.toUInt().toString(16)}"

        private val TETHER_INTERFACE_PATTERN = Regex("[A-Za-z0-9_.:-]{1,15}")
        private val IPV4_ALWAYS_BYPASS_CIDRS = listOf(
            "0.0.0.0/8",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "224.0.0.0/4",
            "240.0.0.0/4",
        )
        private val IPV4_LAN_CIDRS = listOf("10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16")
        private val IPV6_ALWAYS_BYPASS_CIDRS = listOf("::/128", "::1/128", "fe80::/10", "ff00::/8")
        private val IPV6_LAN_CIDRS = listOf("fc00::/7")

        private fun chainNames(appUid: Int): ChainNames {
            require(appUid > 0)
            val suffix = appUid.toString(16)
            return ChainNames(
                guard = "MXG$suffix",
                output = "MXO$suffix",
                slotA = "MXOA$suffix",
                slotB = "MXOB$suffix",
                prerouting = "MXP$suffix",
            )
        }

        private data class ChainNames(
            val guard: String,
            val output: String,
            val slotA: String,
            val slotB: String,
            val prerouting: String,
        ) {
            fun slot(value: String): String = if (value == SLOT_A) slotA else slotB
        }
    }
}
