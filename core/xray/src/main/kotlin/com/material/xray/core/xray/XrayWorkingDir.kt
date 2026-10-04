package com.material.xray.core.xray

import java.io.File

/**
 * A connection's core as it names its own working directory, the app's `files/bin`. The root-mode
 * core runs as a uid that cannot traverse the app's data directory, so it reaches the files and
 * sockets it shares with the app through its cwd instead of their absolute paths.
 */
const val CORE_WORKING_DIR = "/proc/self/cwd"

/**
 * The subdirectory of the working directory that holds the core's sockets. It is the only place
 * the root-mode core may write, so it cannot replace a file the app later reads or writes.
 */
const val CORE_SOCKET_DIR = "sockets"

/** A socket a connection's core creates: the app connects to [path], the core listens on [listenPath]. */
data class CoreSocket(val path: String, val listenPath: String)

fun coreSocket(workingDir: String, name: String): CoreSocket {
    requireFileName(name)
    return CoreSocket("$workingDir/$CORE_SOCKET_DIR/$name", "$CORE_WORKING_DIR/$CORE_SOCKET_DIR/$name")
}

/** How a connection's core names the file [name] in its working directory. */
fun coreWorkingDirPath(name: String): String {
    requireFileName(name)
    return "$CORE_WORKING_DIR/$name"
}

/** Maps [path] from a core's config to one this app can open, given the core's [workingDir]. */
fun resolveCorePath(path: String, workingDir: File): String {
    val relative = path.removePrefix("$CORE_WORKING_DIR/")
    return if (relative == path) path else File(workingDir, relative).path
}

private fun requireFileName(name: String) = require(name.isNotEmpty() && '/' !in name && name != "..") { "Not a file name: $name" }
