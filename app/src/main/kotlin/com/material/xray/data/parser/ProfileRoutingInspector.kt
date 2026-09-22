package com.material.xray.data.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal data class ProfileRouting(
    val rules: List<ProfileRoutingRule>,
    val domainStrategy: String?,
    val domainMatcher: String?,
)

internal data class ProfileRoutingRule(
    val id: String,
    val name: String,
    val target: ProfileRoutingTarget?,
    val domains: List<String>,
    val ips: List<String>,
    val port: String?,
    val protocols: List<String>,
    val additionalConditionFields: List<String>,
    val enabled: Boolean,
    val rawJson: String,
)

internal sealed interface ProfileRoutingTarget {
    val tag: String

    data class Outbound(override val tag: String) : ProfileRoutingTarget

    data class Balancer(override val tag: String) : ProfileRoutingTarget
}

/** Read-only description of routing that remains owned and executed by a raw JSON profile. */
internal object ProfileRoutingInspector {
    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }
    private val ordinaryFields = setOf(
        "type",
        "id",
        "name",
        "__name__",
        "ruleTag",
        "enabled",
        "outboundTag",
        "balancerTag",
        "domain",
        "ip",
        "port",
        "protocol",
    )
    private val routineDefaultRouteFields = setOf(
        "type",
        "id",
        "name",
        "__name__",
        "ruleTag",
        "enabled",
        "outboundTag",
        "balancerTag",
        "network",
    )

    fun inspect(rawConfigJson: String): ProfileRouting? {
        if (rawConfigJson.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(rawConfigJson) as? JsonObject }.getOrNull() ?: return null
        val routing = root["routing"] as? JsonObject ?: return null
        val rules = (routing["rules"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val rule = element as? JsonObject ?: return@mapIndexedNotNull null
            if (rule.isRoutineDefaultRoute()) return@mapIndexedNotNull null
            ProfileRoutingRule(
                id = rule.string("id") ?: rule.string("ruleTag") ?: "profile-rule-${index + 1}",
                name = rule.string("__name__")
                    ?: rule.string("name")
                    ?: rule.string("ruleTag")
                    ?: "Rule ${index + 1}",
                target = rule.string("outboundTag")?.let(ProfileRoutingTarget::Outbound)
                    ?: rule.string("balancerTag")?.let(ProfileRoutingTarget::Balancer),
                domains = rule.stringList("domain"),
                ips = rule.stringList("ip"),
                port = rule.string("port"),
                protocols = rule.stringList("protocol"),
                additionalConditionFields = rule.keys.filterNot(ordinaryFields::contains).sorted(),
                enabled = (rule["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                rawJson = prettyJson.encodeToString(JsonObject.serializer(), rule),
            )
        }
        return ProfileRouting(
            rules = rules,
            domainStrategy = routing.string("domainStrategy"),
            domainMatcher = routing.string("domainMatcher"),
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    private fun JsonObject.isRoutineDefaultRoute(): Boolean {
        if (string("outboundTag") == null && string("balancerTag") == null) return false
        if (keys.any { it !in routineDefaultRouteFields }) return false
        val networks = stringList("network")
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter(String::isNotEmpty)
            .toSet()
        return networks == setOf("tcp", "udp")
    }
}
