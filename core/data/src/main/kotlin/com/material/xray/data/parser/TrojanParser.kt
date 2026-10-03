package com.material.xray.data.parser

import com.material.xray.core.common.text.decodeUriComponentLeniently
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import java.net.URI

object TrojanParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val parsed = URI(uri)
        val password = parsed.rawUserInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIfValidPort() ?: return null
        val fragment = parsed.rawFragment?.let(::decodeUriComponentLeniently).orEmpty()
        val params = parseQuery(parsed.rawQuery ?: "")

        ServerConfig(
            protocol = Protocol.TROJAN,
            name = fragment,
            address = host,
            port = port,
            password = decodeUriComponentLeniently(password),
            transport = ServerConfig.Transport(
                type = params["type"] ?: "tcp",
                path = params["path"].orEmpty(),
                host = params["host"].orEmpty(),
                serviceName = params["serviceName"].orEmpty(),
            ),
            security = ServerConfig.Security(
                type = params["security"] ?: "tls",
                sni = params["sni"].orEmpty(),
                fingerprint = params["fp"].orEmpty(),
                alpn = params["alpn"]?.split(",").orEmpty(),
            ),
            rawUri = uri,
        )
    }.getOrNull()
}
