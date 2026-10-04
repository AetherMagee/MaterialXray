package com.material.xray.telemetry

interface TelemetryClient {
    val isEnabled: Boolean

    fun enable()

    fun disable()

    fun setTag(key: String, value: String)

    fun count(name: String, attributes: Map<String, Any>)

    fun distributionMillis(name: String, value: Long, attributes: Map<String, Any>)

    fun addBreadcrumb(category: String, message: String, data: Map<String, Any>)

    fun startTransaction(name: String, operation: String): TelemetryTransaction

    fun captureException(error: Throwable, fingerprint: List<String>, tags: Map<String, String>)

    fun captureMessage(message: String, fingerprint: String, tags: Map<String, String>)
}

interface TelemetryTransaction {
    fun setTag(key: String, value: String)

    fun startChild(operation: String, description: String): TelemetrySpan

    fun finish(status: TelemetryStatus)
}

enum class TelemetryStatus {
    Ok,
    InternalError,
    Aborted,
    Cancelled,
}
