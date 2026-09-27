package com.material.xray.ui.logs

import androidx.annotation.StringRes
import com.material.xray.R

internal enum class LogFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.logs_filter_all),
    APP(R.string.logs_filter_app),
    XRAY(R.string.logs_filter_xray),
}
