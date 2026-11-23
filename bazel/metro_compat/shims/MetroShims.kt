package org.jetbrains.kotlinconf.bazel.metrocompat.shims

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2DelegateProvider
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeTypeParameterLookupTag

class DiagnosticFactory0Shim(
    private val severity: Severity,
    private val positioningStrategy: AbstractSourceElementPositioningStrategy,
    @Suppress("UNUSED_PARAMETER") psiType: KClass<*>,
    private val container: KtDiagnosticsContainer,
) {
    operator fun provideDelegate(
        thisRef: Any?,
        prop: KProperty<*>,
    ): ReadOnlyProperty<Any?, KtDiagnosticFactory0> =
        DiagnosticFactory0DelegateProvider(severity, positioningStrategy, container)
            .provideDelegate(thisRef, prop)
}

class DiagnosticFactory1Shim<A>(
    private val severity: Severity,
    private val positioningStrategy: AbstractSourceElementPositioningStrategy,
    @Suppress("UNUSED_PARAMETER") psiType: KClass<*>,
    private val container: KtDiagnosticsContainer,
) {
    operator fun provideDelegate(
        thisRef: Any?,
        prop: KProperty<*>,
    ): ReadOnlyProperty<Any?, KtDiagnosticFactory1<A>> =
        DiagnosticFactory1DelegateProvider<A>(severity, positioningStrategy, container)
            .provideDelegate(thisRef, prop)
}

class DiagnosticFactory2Shim<A, B>(
    private val severity: Severity,
    private val positioningStrategy: AbstractSourceElementPositioningStrategy,
    @Suppress("UNUSED_PARAMETER") psiType: KClass<*>,
    private val container: KtDiagnosticsContainer,
) {
    operator fun provideDelegate(
        thisRef: Any?,
        prop: KProperty<*>,
    ): ReadOnlyProperty<Any?, KtDiagnosticFactory2<A, B>> =
        DiagnosticFactory2DelegateProvider<A, B>(severity, positioningStrategy, container)
            .provideDelegate(thisRef, prop)
}

object ConeTypeParameterShim {
    @JvmStatic
    fun getTypeParameterSymbol(tag: ConeTypeParameterLookupTag): FirTypeParameterSymbol =
        (tag as ConeTypeParameterLookupTagImpl).typeParameterSymbol
}
