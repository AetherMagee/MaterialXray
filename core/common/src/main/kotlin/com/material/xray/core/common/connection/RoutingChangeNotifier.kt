package com.material.xray.core.common.connection

/** Tells the running connection that routing it depends on has changed. */
fun interface RoutingChangeNotifier {
    /**
     * Applies [kind] to the active connection. Returns false, and records nothing, when there is no
     * connection to update.
     */
    fun requestActiveConnectionUpdate(kind: PendingRoutingChange): Boolean
}
