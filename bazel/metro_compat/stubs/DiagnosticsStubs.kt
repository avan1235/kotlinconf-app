@file:Suppress("unused", "UNUSED_PARAMETER")

package org.jetbrains.kotlin.diagnostics

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

enum class Severity

abstract class AbstractSourceElementPositioningStrategy

abstract class KtDiagnosticsContainer

class KtDiagnosticFactory0

class KtDiagnosticFactory1<A>

class KtDiagnosticFactory2<A, B>

class DiagnosticFactory0DelegateProvider(
    severity: Severity,
    positioningStrategy: AbstractSourceElementPositioningStrategy,
    container: KtDiagnosticsContainer,
) {
    fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ReadOnlyProperty<Any?, KtDiagnosticFactory0> =
        error("compile-only stub")
}

class DiagnosticFactory1DelegateProvider<A>(
    severity: Severity,
    positioningStrategy: AbstractSourceElementPositioningStrategy,
    container: KtDiagnosticsContainer,
) {
    fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ReadOnlyProperty<Any?, KtDiagnosticFactory1<A>> =
        error("compile-only stub")
}

class DiagnosticFactory2DelegateProvider<A, B>(
    severity: Severity,
    positioningStrategy: AbstractSourceElementPositioningStrategy,
    container: KtDiagnosticsContainer,
) {
    fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ReadOnlyProperty<Any?, KtDiagnosticFactory2<A, B>> =
        error("compile-only stub")
}
