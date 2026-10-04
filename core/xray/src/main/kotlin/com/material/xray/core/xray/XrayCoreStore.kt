package com.material.xray.core.xray

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where an installed core came from. */
@Serializable
enum class XrayCoreSource { Release, File }

/** A core installed next to the bundled one. [id] is also its directory name. */
@Serializable
data class InstalledXrayCore(
    val id: String,
    val version: String,
    val sha256: String,
    val source: XrayCoreSource,
)

/**
 * Cores installed under `files/cores/<id>/`, and which of them replaces the bundled core. Each
 * directory holds the executable, named like the bundled one so a root shell's `pidof` still finds
 * it, and a manifest describing it. A directory without a readable manifest is not a core.
 */
class XrayCoreStore(paths: XrayPaths) {
    private val coresDir = File(paths.filesDir, CORES_DIR)
    private val selectionFile = File(coresDir, SELECTION_FILE)

    fun installed(): List<InstalledXrayCore> = coresDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && isValidCoreId(it.name) }
        .mapNotNull(::readManifest)

    fun selectedId(): String? = runCatching { selectionFile.readText().trim() }
        .getOrNull()
        ?.takeIf(::isValidCoreId)

    /** The selected core's executable, or null when the bundled core is selected or the selection is gone. */
    fun selectedExecutable(): File? = selectedId()
        ?.let(::executableOf)
        ?.takeIf { it.isFile && File(it.parentFile, MANIFEST_FILE).isFile }

    /** Selects [id], or the bundled core when null. */
    fun select(id: String?) {
        if (id == null) {
            if (!selectionFile.delete() && selectionFile.exists()) throw IOException("Could not clear the core selection")
            return
        }
        require(installed().any { it.id == id }) { "Core $id is not installed" }
        coresDir.mkdirs()
        val temporary = File(coresDir, "$SELECTION_FILE.tmp")
        temporary.writeText(id)
        if (!temporary.renameTo(selectionFile)) {
            temporary.delete()
            throw IOException("Could not select core $id")
        }
    }

    /** Deletes [id] unless it is selected. */
    fun delete(id: String) {
        require(isValidCoreId(id)) { "Invalid core id" }
        check(selectedId() != id) { "The selected core cannot be deleted" }
        File(coresDir, id).deleteRecursively()
    }

    /** A fresh directory to assemble a core in before [commit] moves it into place. */
    fun createStagingDir(): File = File(coresDir, "$STAGING_PREFIX${UUID.randomUUID()}").apply {
        if (!mkdirs()) throw IOException("Could not create a staging directory")
    }

    fun stagedExecutable(stagingDir: File): File = File(stagingDir, XRAY_EXECUTABLE_NAME)

    /**
     * Writes [core]'s manifest into [stagingDir] and moves it into place. An id names one build (a
     * release tag or a file's hash), so when that id is already installed the existing copy stays
     * and the staged one is discarded.
     */
    fun commit(stagingDir: File, core: InstalledXrayCore): InstalledXrayCore {
        if (!isValidCoreId(core.id)) throw IOException("Invalid core id ${core.id}")
        val destination = File(coresDir, core.id)
        if (readManifest(destination) != null) {
            stagingDir.deleteRecursively()
            return core
        }
        destination.deleteRecursively()
        File(stagingDir, MANIFEST_FILE).writeText(json.encodeToString(InstalledXrayCore.serializer(), core))
        if (!stagingDir.renameTo(destination)) throw IOException("Could not install core ${core.id}")
        return core
    }

    /** Removes staging directories that an interrupted install left behind. */
    fun removeAbandonedStaging() {
        coresDir.listFiles().orEmpty()
            .filter { it.name.startsWith(STAGING_PREFIX) }
            .forEach(File::deleteRecursively)
    }

    private fun executableOf(id: String): File = File(File(coresDir, id), XRAY_EXECUTABLE_NAME)

    private fun readManifest(dir: File): InstalledXrayCore? {
        val manifest = runCatching {
            json.decodeFromString(InstalledXrayCore.serializer(), File(dir, MANIFEST_FILE).readText())
        }.getOrNull() ?: return null
        return manifest.takeIf { it.id == dir.name && executableOf(it.id).isFile }
    }

    private companion object {
        const val CORES_DIR = "cores"
        const val SELECTION_FILE = "selected"
        const val MANIFEST_FILE = "core.json"
        const val STAGING_PREFIX = ".staging-"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private val CORE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/** Ids double as directory names, so they may not contain separators or start with a dot. */
fun isValidCoreId(id: String): Boolean = CORE_ID_PATTERN.matches(id)
