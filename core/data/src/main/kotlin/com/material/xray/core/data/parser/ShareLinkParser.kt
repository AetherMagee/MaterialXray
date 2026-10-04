package com.material.xray.core.data.parser

import com.material.xray.core.model.ServerConfig

class ShareLinkParser {

    fun parse(uri: String): ServerConfig? = parseWith(directParsers, uri.trim())

    fun parseMultiple(text: String): List<ServerConfig> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { parseWith(subscriptionParsers, it) }

    private fun parseWith(parsers: Map<String, (String) -> ServerConfig?>, uri: String): ServerConfig? = parsers.entries.firstOrNull { (scheme, _) -> uri.startsWith(scheme) }?.value?.invoke(uri)

    internal companion object {
        /** Links a user can add on their own. */
        private val directParsers: Map<String, (String) -> ServerConfig?> = mapOf(
            "vless://" to VlessParser::parse,
            "vmess://" to VmessParser::parse,
            "trojan://" to TrojanParser::parse,
            "ss://" to ShadowsocksParser::parse,
            "hysteria2://" to Hysteria2Parser::parse,
            "hy2://" to Hysteria2Parser::parse,
        )

        /** Subscriptions also carry proxies that only make sense as part of a provider's list. */
        private val subscriptionParsers: Map<String, (String) -> ServerConfig?> = directParsers + mapOf(
            "http://" to HttpProxyParser::parse,
            "https://" to HttpProxyParser::parse,
            "socks://" to SocksParser::parse,
            "socks5://" to SocksParser::parse,
            "socks5h://" to SocksParser::parse,
            "wireguard://" to WireGuardParser::parse,
            "wg://" to WireGuardParser::parse,
        )

        val directSchemes: Set<String> = directParsers.keys
        val subscriptionSchemes: Set<String> = subscriptionParsers.keys
    }
}
