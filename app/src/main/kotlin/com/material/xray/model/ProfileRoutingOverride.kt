package com.material.xray.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A local delta against one routing rule supplied inside a JSON profile. */
@Serializable
data class ProfileRoutingOverride(
    val originalRuleJson: String,
    val originalIndex: Int,
    val replacement: RoutingRule? = null,
    val enabled: Boolean = true,
    val orphaned: Boolean = false,
)

object ProfileRoutingOverrideEngine {
    private val json = Json

    fun apply(rawConfigJson: String, overrides: List<ProfileRoutingOverride>): String {
        if (overrides.isEmpty()) return rawConfigJson
        val root = rawConfigJson.objectOrNull() ?: return rawConfigJson
        val routing = root["routing"] as? JsonObject ?: return rawConfigJson
        val rules = routing["rules"] as? JsonArray ?: return rawConfigJson
        val matches = overrides.matchingRules(rules)
        if (matches.isEmpty()) return rawConfigJson

        val patchedRules = buildList {
            rules.forEachIndexed { index, rule ->
                val override = matches[index]
                if (override == null) {
                    add(rule)
                } else if (override.enabled) {
                    addAll(override.replacement?.toXrayRules() ?: listOf(rule))
                }
            }
        }
        val patchedRouting = JsonObject(routing + ("rules" to JsonArray(patchedRules)))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("routing" to patchedRouting)))
    }

    fun reconcile(
        rawConfigJson: String,
        overrides: List<ProfileRoutingOverride>,
    ): List<ProfileRoutingOverride> {
        if (overrides.isEmpty()) return emptyList()
        val rules = rawConfigJson.objectOrNull()
            ?.get("routing")
            ?.let { it as? JsonObject }
            ?.get("rules")
            ?.let { it as? JsonArray }
            ?: return overrides.map { it.copy(orphaned = true) }
        val matchedOverrides = overrides.matchingRules(rules).values.toSet()
        return overrides.map { override -> override.copy(orphaned = override !in matchedOverrides) }
    }

    fun replace(
        overrides: List<ProfileRoutingOverride>,
        originalRuleJson: String,
        originalIndex: Int,
        replacement: RoutingRule?,
        enabled: Boolean,
    ): List<ProfileRoutingOverride> {
        val canonicalOriginal = originalRuleJson.objectOrNull()?.canonicalJson() ?: return overrides
        val existingIndex = overrides.indexOfFirst {
            it.originalIndex == originalIndex && it.originalRuleJson.objectOrNull() == canonicalOriginal.objectOrNull()
        }.takeIf { it >= 0 } ?: overrides.indexOfFirst {
            it.originalRuleJson.objectOrNull() == canonicalOriginal.objectOrNull()
        }
        if (enabled && replacement == null) {
            return if (existingIndex < 0) overrides else overrides.toMutableList().apply { removeAt(existingIndex) }
        }
        val updated = ProfileRoutingOverride(
            originalRuleJson = canonicalOriginal,
            originalIndex = originalIndex,
            replacement = replacement,
            enabled = enabled,
        )
        if (existingIndex < 0) return overrides + updated
        return overrides.toMutableList().apply { set(existingIndex, updated) }
    }

    private fun List<ProfileRoutingOverride>.matchingRules(rules: JsonArray): Map<Int, ProfileRoutingOverride> {
        val availableIndices = rules.indices.toMutableSet()
        return buildMap {
            this@matchingRules.filterNot(ProfileRoutingOverride::orphaned).forEach { override ->
                val original = override.originalRuleJson.objectOrNull() ?: return@forEach
                val index = override.originalIndex.takeIf { it in availableIndices && rules[it] == original }
                    ?: availableIndices.firstOrNull { rules[it] == original }
                    ?: return@forEach
                put(index, override)
                availableIndices.remove(index)
            }
        }
    }

    private fun String.objectOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(this) as? JsonObject
    }.getOrNull()

    private fun JsonObject.canonicalJson(): String = json.encodeToString(JsonObject.serializer(), this)
}
