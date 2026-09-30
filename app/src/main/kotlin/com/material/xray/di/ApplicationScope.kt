package com.material.xray.di

import org.koin.core.annotation.Qualifier

/**
 * Qualifies the process-wide [kotlinx.coroutines.CoroutineScope] that outlives every screen and
 * service. Work launched in it is only cancelled when the process dies.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
)
annotation class ApplicationScope
