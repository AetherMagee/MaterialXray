package com.material.xray.ui.text

import androidx.annotation.StringRes
import com.material.xray.R
import com.material.xray.core.network.Ipv6SessionState
import com.material.xray.model.AppUpdateInterval
import com.material.xray.model.DnsPreset
import com.material.xray.model.Ipv6Mode
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationStyle
import com.material.xray.model.OtherVpnMode
import com.material.xray.model.PingMethod
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound

@get:StringRes
val AppUpdateInterval.labelResource: Int
    get() = when (this) {
        AppUpdateInterval.TwelveHours -> R.string.settings_update_interval_twelve_hours
        AppUpdateInterval.OneDay -> R.string.settings_update_interval_one_day
        AppUpdateInterval.ThreeDays -> R.string.settings_update_interval_three_days
        AppUpdateInterval.OneWeek -> R.string.settings_update_interval_one_week
    }

@get:StringRes
val AppUpdateInterval.descriptionResource: Int
    get() = when (this) {
        AppUpdateInterval.TwelveHours -> R.string.settings_update_interval_twelve_hours_description
        AppUpdateInterval.OneDay -> R.string.settings_update_interval_one_day_description
        AppUpdateInterval.ThreeDays -> R.string.settings_update_interval_three_days_description
        AppUpdateInterval.OneWeek -> R.string.settings_update_interval_one_week_description
    }

@get:StringRes
val PingMethod.labelResource: Int
    get() = when (this) {
        PingMethod.Httping -> R.string.ping_method_httping_label
        PingMethod.Tcping -> R.string.ping_method_tcping_label
    }

@get:StringRes
val PingMethod.descriptionResource: Int
    get() = when (this) {
        PingMethod.Httping -> R.string.ping_method_httping_description
        PingMethod.Tcping -> R.string.ping_method_tcping_description
    }

@get:StringRes
val SubscriptionUserAgentMode.labelResource: Int
    get() = when (this) {
        SubscriptionUserAgentMode.AUTO -> R.string.subscription_user_agent_automatic_label
        SubscriptionUserAgentMode.HAPP -> R.string.subscription_user_agent_happ_label
        SubscriptionUserAgentMode.CUSTOM -> R.string.subscription_user_agent_custom_label
    }

@get:StringRes
val SubscriptionUserAgentMode.descriptionResource: Int
    get() = when (this) {
        SubscriptionUserAgentMode.AUTO -> R.string.subscription_user_agent_automatic_description
        SubscriptionUserAgentMode.HAPP -> R.string.subscription_user_agent_happ_description
        SubscriptionUserAgentMode.CUSTOM -> R.string.subscription_user_agent_custom_description
    }

@get:StringRes
val RoutingPolicyControl.labelResource: Int
    get() = when (this) {
        RoutingPolicyControl.User -> R.string.routing_policy_user_label
        RoutingPolicyControl.SubscriptionProvider -> R.string.routing_policy_subscription_provider_label
    }

@get:StringRes
val RoutingPolicyControl.descriptionResource: Int
    get() = when (this) {
        RoutingPolicyControl.User -> R.string.routing_policy_user_description
        RoutingPolicyControl.SubscriptionProvider -> R.string.routing_policy_subscription_provider_description
    }

@get:StringRes
val Ipv6SessionState.labelResource: Int
    get() = when (this) {
        Ipv6SessionState.Enabled -> R.string.ipv6_session_enabled
        Ipv6SessionState.EnabledUntilReconnect -> R.string.ipv6_session_enabled_until_reconnect
        Ipv6SessionState.NoIpv6Network -> R.string.ipv6_session_no_ipv6_network
        Ipv6SessionState.CheckFailed -> R.string.ipv6_session_check_failed
    }

@get:StringRes
val Ipv6Mode.labelResource: Int
    get() = when (this) {
        Ipv6Mode.Off -> R.string.ipv6_mode_off_label
        Ipv6Mode.Auto -> R.string.ipv6_mode_auto_label
        Ipv6Mode.On -> R.string.ipv6_mode_on_label
    }

@get:StringRes
val Ipv6Mode.descriptionResource: Int
    get() = when (this) {
        Ipv6Mode.Off -> R.string.ipv6_mode_off_description
        Ipv6Mode.Auto -> R.string.ipv6_mode_auto_description
        Ipv6Mode.On -> R.string.ipv6_mode_on_description
    }

@get:StringRes
val OtherVpnMode.labelResource: Int
    get() = when (this) {
        OtherVpnMode.AutoRouting -> R.string.other_vpn_mode_auto_routing_label
        OtherVpnMode.TunnelInTunnel -> R.string.other_vpn_mode_tunnel_in_tunnel_label
        OtherVpnMode.StandDown -> R.string.other_vpn_mode_stand_down_label
    }

@get:StringRes
val OtherVpnMode.descriptionResource: Int
    get() = when (this) {
        OtherVpnMode.AutoRouting -> R.string.other_vpn_mode_auto_routing_description
        OtherVpnMode.TunnelInTunnel -> R.string.other_vpn_mode_tunnel_in_tunnel_description
        OtherVpnMode.StandDown -> R.string.other_vpn_mode_stand_down_description
    }

@get:StringRes
val RootConnectionBackend.labelResource: Int
    get() = when (this) {
        RootConnectionBackend.Tun -> R.string.root_connection_backend_tun_label
        RootConnectionBackend.Tproxy -> R.string.root_connection_backend_tproxy_label
    }

@get:StringRes
val RootConnectionBackend.descriptionResource: Int
    get() = when (this) {
        RootConnectionBackend.Tun -> R.string.root_connection_backend_tun_description
        RootConnectionBackend.Tproxy -> R.string.root_connection_backend_tproxy_description
    }

@get:StringRes
val XrayOutbound.labelResource: Int
    get() = when (this) {
        XrayOutbound.Proxy -> R.string.xray_outbound_proxy_label
        XrayOutbound.Direct -> R.string.xray_outbound_direct_label
        XrayOutbound.Block -> R.string.xray_outbound_block_label
    }

@get:StringRes
val XrayOutbound.descriptionResource: Int
    get() = when (this) {
        XrayOutbound.Proxy -> R.string.xray_outbound_proxy_description
        XrayOutbound.Direct -> R.string.xray_outbound_direct_description
        XrayOutbound.Block -> R.string.xray_outbound_block_description
    }

/** Describes what happens when a routing rule with no matching condition sends traffic here. */
@get:StringRes
val XrayOutbound.catchAllEffectResource: Int
    get() = when (this) {
        XrayOutbound.Proxy -> R.string.routing_catch_all_effect_proxy
        XrayOutbound.Direct -> R.string.routing_catch_all_effect_direct
        XrayOutbound.Block -> R.string.routing_catch_all_effect_block
    }

@get:StringRes
val XrayLogLevel.labelResource: Int
    get() = when (this) {
        XrayLogLevel.Debug -> R.string.xray_log_level_debug
        XrayLogLevel.Info -> R.string.xray_log_level_info
        XrayLogLevel.Warning -> R.string.xray_log_level_warning
        XrayLogLevel.Error -> R.string.xray_log_level_error
        XrayLogLevel.None -> R.string.xray_log_level_none
    }

@get:StringRes
val LauncherIcon.labelResource: Int
    get() = when (this) {
        LauncherIcon.Default -> R.string.launcher_icon_default
        LauncherIcon.Material -> R.string.launcher_icon_material
    }

@get:StringRes
val NotificationField.labelResource: Int
    get() = when (this) {
        NotificationField.TrafficSpeed -> R.string.notification_field_traffic_speed_label
        NotificationField.RamUsage -> R.string.notification_field_ram_usage_label
        NotificationField.ConnectionCount -> R.string.notification_field_connection_count_label
        NotificationField.Ping -> R.string.notification_field_ping_label
        NotificationField.SessionTraffic -> R.string.notification_field_session_traffic_label
    }

@get:StringRes
val NotificationField.descriptionResource: Int
    get() = when (this) {
        NotificationField.TrafficSpeed -> R.string.notification_field_traffic_speed_description
        NotificationField.RamUsage -> R.string.notification_field_ram_usage_description
        NotificationField.ConnectionCount -> R.string.notification_field_connection_count_description
        NotificationField.Ping -> R.string.notification_field_ping_description
        NotificationField.SessionTraffic -> R.string.notification_field_session_traffic_description
    }

@get:StringRes
val NotificationStyle.labelResource: Int
    get() = when (this) {
        NotificationStyle.Normal -> R.string.notification_style_normal_label
        NotificationStyle.Compact -> R.string.notification_style_compact_label
    }

@get:StringRes
val NotificationStyle.descriptionResource: Int
    get() = when (this) {
        NotificationStyle.Normal -> R.string.notification_style_normal_description
        NotificationStyle.Compact -> R.string.notification_style_compact_description
    }

@get:StringRes
val DnsPreset.labelResource: Int
    get() = when (this) {
        DnsPreset.System -> R.string.dns_preset_system_label
        DnsPreset.Cloudflare -> R.string.dns_preset_cloudflare_label
        DnsPreset.CloudflareSecurity -> R.string.dns_preset_cloudflare_security_label
        DnsPreset.Google -> R.string.dns_preset_google_label
        DnsPreset.Quad9 -> R.string.dns_preset_quad9_label
        DnsPreset.AdGuard -> R.string.dns_preset_adguard_label
        DnsPreset.Yandex -> R.string.dns_preset_yandex_label
        DnsPreset.Custom -> R.string.dns_preset_custom_label
    }

@get:StringRes
val DnsPreset.descriptionResource: Int
    get() = when (this) {
        DnsPreset.System -> R.string.dns_preset_system_description
        DnsPreset.Cloudflare -> R.string.dns_preset_cloudflare_description
        DnsPreset.CloudflareSecurity -> R.string.dns_preset_cloudflare_security_description
        DnsPreset.Google -> R.string.dns_preset_google_description
        DnsPreset.Quad9 -> R.string.dns_preset_quad9_description
        DnsPreset.AdGuard -> R.string.dns_preset_adguard_description
        DnsPreset.Yandex -> R.string.dns_preset_yandex_description
        DnsPreset.Custom -> R.string.dns_preset_custom_description
    }
