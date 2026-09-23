package com.material.xray.ui.routing

import com.material.xray.model.RoutingRule
import kotlinx.serialization.Serializable

@Serializable
data class EditableRoutingRule(
    val rule: RoutingRule,
    val isNew: Boolean,
    val profileOriginalRuleJson: String? = null,
    val profileOriginalIndex: Int? = null,
    val rawJson: String? = null,
)
