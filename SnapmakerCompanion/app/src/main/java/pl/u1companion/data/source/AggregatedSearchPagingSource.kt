package pl.u1companion.data.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.category.SourcePlan
import pl.u1companion.geometry.ModelFacts

/**
 * Strumień wyników: jedno źródło + jedna natywna kategoria (null = bez kategorii).
 * Kategoria kanoniczna może mapować się na kilka kategorii natywnych -> kilka strumieni z jednego źródła.
 */
data class StreamKey(val source: SourceId, val categoryId: String?) {
    override fun toString() = "${source.name}#${categoryId ?: "*"}"
}

/** Klucz strony: osobny kursor dla każdego strumienia; brak wpisu = strumień wyczerpany. */
data class AggregateKey(val cursors: Map<StreamKey, String?>)

/** Ostrzeżenie o częściowej awarii (jedno źródło padło, reszta działa). */
data class SourceWarning(val source: SourceId, val error: Throwable)

/**
 * PagingSource łączący wyniki z wielu [ModelSource] (infinite scroll).
 * - Każdy aktywny strumień pobiera stronę równolegle.
 * - Wyniki są przeplatane (round-robin) - globalne sortowanie między serwisami nie ma sensu,
 *   bo metryki (likes/downloads) są nieporównywalne.
 * - Błąd jednego strumienia nie przerywa listy - trafia do [onWarning]; jeśli padły wszystkie -> LoadResult.Error.
 * - Filtry klienckie ([ClientSideFilter]) z faktami z analizy plików ([facts]).
 */
class AggregatedSearchPagingSource(
    private val plans: List<SourcePlan>,
    private val query: SearchQuery,
    private val pageSizePerSource: Int,
    private val facts: () -> Map<String, ModelFacts> = { emptyMap() },
    private val onWarning: (SourceWarning) -> Unit,
) : PagingSource<AggregateKey, ModelSummary>() {

    private val planBySource = plans.associateBy { it.source.id }

    override suspend fun load(params: LoadParams<AggregateKey>): LoadResult<AggregateKey, ModelSummary> {
        var key = params.key ?: initialKey(plans)
        // Filtry po stronie klienta mogą wyciąć całą stronę - pusta strona z nextKey potrafi
        // zatrzymać Paging, więc dociągamy kolejne strony (max 3 próby).
        repeat(MAX_EMPTY_PAGE_RETRIES) {
            val result = loadOnce(key)
            if (result !is LoadResult.Page || result.data.isNotEmpty() || result.nextKey == null) return result
            key = result.nextKey!!
        }
        return loadOnce(key)
    }

    private suspend fun loadOnce(key: AggregateKey): LoadResult<AggregateKey, ModelSummary> {
        val active = key.cursors.keys.toList()
        if (active.isEmpty()) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        val known = facts()

        val results = coroutineScope {
            active.map { sk ->
                async {
                    val plan = planBySource.getValue(sk.source)
                    sk to runCatching {
                        if (!plan.source.isAvailable()) throw SourceException.NotConfigured(sk.source)
                        plan.source.search(query, PageRequest(key.cursors[sk], pageSizePerSource, sk.categoryId))
                    }
                }
            }.map { it.await() }
        }

        val nextCursors = mutableMapOf<StreamKey, String?>()
        val perStream = mutableListOf<List<ModelSummary>>()
        var failures = 0
        for ((sk, r) in results) {
            val plan = planBySource.getValue(sk.source)
            r.onSuccess { page ->
                perStream += page.items.filter {
                    ClientSideFilter.accept(it, query.filters, known[it.key], plan.clientCategory, plan.categories)
                }
                if (page.nextCursor != null) nextCursors[sk] = page.nextCursor
            }.onFailure { e ->
                failures++
                onWarning(SourceWarning(sk.source, e))
            }
        }
        if (failures == results.size) {
            return LoadResult.Error(results.first().second.exceptionOrNull() ?: IllegalStateException("Brak wyników"))
        }
        val merged = interleave(perStream).distinctBy { it.key }
        val nextKey = if (nextCursors.isEmpty()) null else AggregateKey(nextCursors)
        return LoadResult.Page(merged, prevKey = null, nextKey = nextKey)
    }

    override fun getRefreshKey(state: PagingState<AggregateKey, ModelSummary>): AggregateKey? = null

    companion object {
        private const val MAX_EMPTY_PAGE_RETRIES = 3

        fun initialKey(plans: List<SourcePlan>) = AggregateKey(
            plans.flatMap { p -> p.nativeCategoryIds.map { StreamKey(p.source.id, it) } }.associateWith { null },
        )

        internal fun interleave(lists: List<List<ModelSummary>>): List<ModelSummary> {
            val out = ArrayList<ModelSummary>(lists.sumOf { it.size })
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
            return out
        }
    }
}
