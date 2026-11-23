package org.jetbrains.kotlin.fir.symbols

import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeTypeParameterLookupTag

class ConeTypeParameterLookupTagImpl : ConeTypeParameterLookupTag() {
    val typeParameterSymbol: FirTypeParameterSymbol
        get() = error("compile-only stub")
}
