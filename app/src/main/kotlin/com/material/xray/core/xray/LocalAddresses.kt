package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

/** Exact host addresses in the root network namespace, including downstream tether interfaces. */
internal object LocalAddresses {
    const val COMMAND = "ip -o address show"

    suspend fun read(execute: suspend (String) -> RootShell.Result): List<String> {
        val result = execute(COMMAND)
        if (!result.isSuccess) throw IOException("Could not inspect local interface addresses: ${result.error}")
        return parse(result.output)
    }

    fun parse(output: String): List<String> {
        val addresses = output.lineSequence().filter(String::isNotBlank).map { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val familyIndex = fields.indexOfFirst { it == "inet" || it == "inet6" }
            // ip also prints link-layer entries; they carry no address for our rules.
            if (familyIndex < 0) return@map null
            val address = fields.getOrNull(familyIndex + 1)?.substringBefore('/')
                .orEmpty()
            hostCidr(address) ?: throw IOException("Invalid local interface address: $address")
        }.filterNotNull().toSet()
        if (addresses.isEmpty()) throw IOException("Local interface address inspection returned no IP addresses")
        return addresses.sorted()
    }

    fun hostCidr(address: String): String? {
        if (':' in address) {
            if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return null
            val parsed = runCatching { InetAddress.getByName(address) }.getOrNull() as? Inet6Address ?: return null
            return "${parsed.hostAddress}/128"
        }
        val parts = address.split('.')
        if (parts.size != 4) return null
        if (parts.any { it.any { c -> !c.isDigit() } || it.toIntOrNull() !in 0..255 }) return null
        return "${parts.joinToString(".") { it.toInt().toString() }}/32"
    }

    fun forTool(addresses: List<String>, tool: String): List<String> {
        require(addresses.all { cidr -> hostCidr(cidr.substringBefore('/')) == cidr })
        return if (tool.startsWith("ip6tables")) {
            (addresses.filter { ':' in it } + "0:0:0:0:0:0:0:1/128").distinct()
        } else {
            (listOf("127.0.0.0/8") + addresses.filter { ':' !in it && !it.startsWith("127.") }).distinct()
        }
    }
}
