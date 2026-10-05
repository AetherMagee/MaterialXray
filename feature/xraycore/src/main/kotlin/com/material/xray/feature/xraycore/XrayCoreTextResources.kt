package com.material.xray.feature.xraycore

import androidx.annotation.StringRes
import com.material.xray.core.ui.R
import com.material.xray.core.xraycore.XrayCoreUpdateInterval

@get:StringRes
internal val XrayCoreUpdateInterval.labelResource: Int
    get() = when (this) {
        XrayCoreUpdateInterval.ThreeDays -> R.string.settings_update_interval_three_days
        XrayCoreUpdateInterval.OneWeek -> R.string.settings_update_interval_one_week
        XrayCoreUpdateInterval.TwoWeeks -> R.string.settings_xray_core_interval_two_weeks
        XrayCoreUpdateInterval.OneMonth -> R.string.settings_xray_core_interval_one_month
    }

@get:StringRes
internal val XrayCoreUpdateInterval.descriptionResource: Int
    get() = when (this) {
        XrayCoreUpdateInterval.ThreeDays -> R.string.settings_update_interval_three_days_description
        XrayCoreUpdateInterval.OneWeek -> R.string.settings_update_interval_one_week_description
        XrayCoreUpdateInterval.TwoWeeks -> R.string.settings_xray_core_interval_two_weeks_description
        XrayCoreUpdateInterval.OneMonth -> R.string.settings_xray_core_interval_one_month_description
    }
