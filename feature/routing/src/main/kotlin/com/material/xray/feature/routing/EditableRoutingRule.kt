package com.material.xray.feature.routing

import com.material.xray.core.model.RoutingRule
import kotlinx.serialization.Serializable

@Serializable
data class EditableRoutingRule(
    val rule: RoutingRule,
    val isNew: Boolean,
    val profileOriginalRuleJson: String? = null,
    val profileOriginalIndex: Int? = null,
    val rawJson: String? = null,
    val subscriptionWide: Boolean = false,
) {
    /** Whether the rule came from the subscription, so editing it while automatic takes routing manual. */
    val providerSourced: Boolean get() = subscriptionWide || profileOriginalRuleJson != null
}
