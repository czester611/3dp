package pl.u1companion.data.source

import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchFilters

/**
 * Filtry realizowane po stronie aplikacji dla serwisów, które nie wspierają ich w API.
 * Zasada: jeśli informacja jest nieznana (null) - NIE odrzucamy wyniku w liście,
 * tylko oznaczamy go w UI ("?"). Wyjątek: tryb ścisły (strict) dla "tylko z G-code dla U1".
 */
object ClientSideFilter {

    fun accept(item: ModelSummary, f: SearchFilters, strict: Boolean = false): Boolean {
        if (f.onlyFree && !item.isFree) return false
        if (f.licenses.isNotEmpty()) {
            val fam = item.license?.family ?: return !strict
            if (fam !in f.licenses) return false
        }
        if (f.onlyCommercialUse && item.license?.family?.commercial == false) return false
        if (f.onlyPrintReady && item.hasPrintReadyFile == false) return false
        if (f.onlyPrintReady && strict && item.hasPrintReadyFile == null) return false
        if (f.onlyU1Profile && item.hasU1Profile != true) return false
        // onlyFitsU1: wymiary znane dopiero w szczegółach (lub po analizie STL) - filtr stosowany
        // w widoku szczegółów i w bibliotece; w liście tylko oznaczenie.
        return true
    }
}
