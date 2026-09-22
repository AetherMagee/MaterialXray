package com.material.xray.data.parser

import com.material.xray.model.ProfileRoutingOverride
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayOutbound
import com.material.xray.model.toXrayRules
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
    val originalRuleJson: String,
    val originalIndex: Int,
    val editableRule: RoutingRule?,
    val locallyModified: Boolean,
    val locallyEdited: Boolean,
    val orphaned: Boolean,
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

    fun inspect(rawConfigJson: String): ProfileRouting? = inspect(rawConfigJson, emptyList())

    fun inspect(config: ServerConfig): ProfileRouting? = inspect(config.rawConfigJson, config.profileRoutingOverrides)

    private fun inspect(
        rawConfigJson: String,
        overrides: List<ProfileRoutingOverride>,
    ): ProfileRouting? {
        val root = runCatching { json.parseToJsonElement(rawConfigJson) as? JsonObject }.getOrNull()
        val routing = root?.get("routing") as? JsonObject
        if (routing == null && overrides.none(ProfileRoutingOverride::orphaned)) return null
        val rawRules = routing?.get("rules") as? JsonArray ?: JsonArray(emptyList())
        val matchedOverrides = overrides.matchingRules(rawRules)
        val rules = rawRules.mapIndexedNotNull { index, element ->
            val rule = element as? JsonObject ?: return@mapIndexedNotNull null
            if (rule.isRoutineDefaultRoute()) return@mapIndexedNotNull null
            describeRule(rule, index, matchedOverrides[index])
        }
        val orphanedRules = overrides.filter(ProfileRoutingOverride::orphaned).mapNotNull { override ->
            val original = runCatching { json.parseToJsonElement(override.originalRuleJson) as? JsonObject }.getOrNull()
                ?: return@mapNotNull null
            describeRule(original, override.originalIndex, override)
        }
        return ProfileRouting(
            rules = rules + orphanedRules,
            domainStrategy = routing?.string("domainStrategy"),
            domainMatcher = routing?.string("domainMatcher"),
        )
    }

    private fun describeRule(
        original: JsonObject,
        index: Int,
        override: ProfileRoutingOverride?,
    ): ProfileRoutingRule {
        val replacement = override?.replacement
        val replacementRules = replacement?.toXrayRules()
        val effective = replacementRules?.singleOrNull() ?: original
        val id = original.string("id") ?: original.string("ruleTag") ?: "profile-rule-${index + 1}"
        val name = original.displayName(index, replacement)
        val target = effective.target(replacement)
        val editableRule = replacement ?: effective.toEditableRule(id, name)
        val rawElement = replacementRules?.singleOrNull() ?: replacementRules?.let(::JsonArray) ?: effective
        return ProfileRoutingRule(
            id = id,
            name = name,
            target = target,
            domains = replacement?.domains ?: effective.stringList("domain"),
            ips = replacement?.ips ?: effective.stringList("ip"),
            port = replacement?.port ?: effective.string("port"),
            protocols = replacement?.protocols ?: effective.stringList("protocol"),
            additionalConditionFields = if (replacement != null) {
                emptyList()
            } else {
                effective.keys.filterNot(ordinaryFields::contains).sorted()
            },
            enabled = override?.enabled ?: ((original["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true),
            rawJson = prettyJson.encodeToString(rawElement),
            originalRuleJson = json.encodeToString(JsonObject.serializer(), original),
            originalIndex = index,
            editableRule = editableRule,
            locallyModified = override != null,
            locallyEdited = replacement != null,
            orphaned = override?.orphaned == true,
        )
    }

    private fun JsonObject.displayName(index: Int, replacement: RoutingRule?): String = replacement?.name
        ?: string("__name__")
        ?: string("name")
        ?: string("ruleTag")
        ?: "Rule ${index + 1}"

    private fun JsonObject.target(replacement: RoutingRule?): ProfileRoutingTarget? = replacement
        ?.let { ProfileRoutingTarget.Outbound(it.outboundTag) }
        ?: string("outboundTag")?.let(ProfileRoutingTarget::Outbound)
        ?: string("balancerTag")?.let(ProfileRoutingTarget::Balancer)

    private fun JsonObject.toEditableRule(id: String, name: String): RoutingRule? {
        if (string("type")?.equals("field", ignoreCase = true) == false) return null
        val outboundTag = string("outboundTag") ?: return null
        if (XrayOutbound.fromTagOrNull(outboundTag) == null) return null
        if (keys.any { it !in ordinaryFields }) return null
        return RoutingRule(
            id = id,
            name = name,
            outboundTag = outboundTag,
            domains = stringList("domain"),
            ips = stringList("ip"),
            port = string("port"),
            protocols = stringList("protocol"),
            operator = RoutingRuleOperator.AND,
        )
    }

    private fun List<ProfileRoutingOverride>.matchingRules(rules: JsonArray): Map<Int, ProfileRoutingOverride> {
        val availableIndices = rules.indices.toMutableSet()
        return buildMap {
            this@matchingRules.filterNot(ProfileRoutingOverride::orphaned).forEach { override ->
                val original = runCatching {
                    json.parseToJsonElement(override.originalRuleJson) as? JsonObject
                }.getOrNull() ?: return@forEach
                val index = override.originalIndex.takeIf { it in availableIndices && rules[it] == original }
                    ?: availableIndices.firstOrNull { rules[it] == original }
                    ?: return@forEach
                put(index, override)
                availableIndices.remove(index)
            }
        }
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
