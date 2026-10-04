package com.material.xray.feature.settings

import androidx.compose.runtime.Composable

/**
 * A Settings page that only some builds contain, such as the Xray core manager. :app supplies it,
 * so this module does not depend on the feature behind it; without it the Core section has no row
 * for it.
 */
class OptionalSettingsPage(
    /** The supporting text of the page's row in the Core section. */
    val summary: @Composable () -> String,
    /** The page, drawn over the settings list like the DNS page. */
    val content: @Composable (useRootService: Boolean, onBack: () -> Unit) -> Unit,
)
