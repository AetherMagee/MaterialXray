package com.material.xray.core.xraycore

import com.material.xray.core.xray.XrayPaths
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A consecutive set of version-picker pages for this device's ABI; refreshing page 1 replaces it. */
internal class XrayCoreReleaseCache(paths: XrayPaths, private val abi: String) {
    private val file = File(paths.cacheDir, "xray-core-releases.json")
    private var cachedPages = read()

    val pages: List<XrayCoreReleasePage>
        @Synchronized get() = cachedPages

    @Synchronized
    fun save(page: Int, result: XrayCoreReleasePage) {
        if (page !in 1..cachedPages.size + 1) return
        cachedPages = cachedPages.take(page - 1) + result
        // Cache failure must not turn a successfully loaded release list into a network error.
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(abi, cachedPages)))
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }

    private fun read(): List<XrayCoreReleasePage> = runCatching {
        json.decodeFromString(Snapshot.serializer(), file.readText())
            .takeIf { it.abi == abi }?.pages.orEmpty()
    }.getOrDefault(emptyList())

    @Serializable
    private data class Snapshot(val abi: String, val pages: List<XrayCoreReleasePage>)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
