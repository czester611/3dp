package pl.u1companion.data.source

import pl.u1companion.core.model.CanonicalCategory
import pl.u1companion.core.model.Category
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.data.category.CategoryMapper
import pl.u1companion.geometry.FitStatus
import pl.u1companion.geometry.ModelFacts

/**
 * Filtry realizowane po stronie aplikacji (dla serwisów, które nie wspierają ich w API, oraz dla danych
 * znanych dopiero po pobraniu pliku - wymiary, profil z 3MF).
 *
 * Zasada: jeśli informacja jest nieznana (null/UNKNOWN) - NIE odrzucamy wyniku, tylko UI pokazuje "?".
 * Wyjątki: "tylko z profilem U1" wymaga potwierdzenia (nazwa pliku lub analiza 3MF), tryb [strict].
 */
object ClientSideFilter {

    fun accept(
        item: ModelSummary,
        f: SearchFilters,
        facts: ModelFacts? = null,
        clientCategory: CanonicalCategory? = null,
        categories: List<Category> = emptyList(),
        strict: Boolean = false,
    ): Boolean {
        if (f.onlyFree && !item.isFree) return false
        if (f.licenses.isNotEmpty()) {
            val fam = item.license?.family ?: return !strict
            if (fam !in f.licenses) return false
        }
        if (f.onlyCommercialUse && item.license?.family?.commercial == false) return false
        if (f.onlyPrintReady && item.hasPrintReadyFile == false) return false
        if (f.onlyPrintReady && strict && item.hasPrintReadyFile == null) return false
        if (f.onlyU1Profile && !hasU1Profile(item, facts)) return false
        if (f.onlyFitsU1 && facts?.fit == FitStatus.TOO_BIG) return false
        if (clientCategory != null) {
            val c = CategoryMapper.canonicalForName(item.category, categories)
            if (c != null && c != clientCategory) return false
            if (c == null && strict) return false
        }
        return true
    }

    fun hasU1Profile(item: ModelSummary, facts: ModelFacts?): Boolean =
        item.hasU1Profile == true || facts?.u1Profile == true
}
