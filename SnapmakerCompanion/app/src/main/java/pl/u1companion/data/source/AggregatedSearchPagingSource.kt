package pl.u1companion.data.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId

/** Klucz strony: osobny kursor dla każdego źródła; brak wpisu = źródło wyczerpane. */
data class AggregateKey(val cursors: Map<SourceId, String?>)

/** Ostrzeżenie o częściowej awarii (jedno źródło padło, reszta działa). */
data class SourceWarning(val source: SourceId, val error: Throwable)

/**
 * PagingSource łączący wyniki z wielu [ModelSource] (infinite scroll).
 * - Każde aktywne źródło pobiera stronę równolegle.
 * - Wyniki są przeplatane (round-robin) - globalne sortowanie między serwisami nie ma sensu,
 *   bo metryki (likes/downloads) są nieporównywalne.
 * - Błąd jednego źródła nie przerywa listy - trafia do [onWarning]; jeśli padły wszystkie -> LoadResult.Error.
 */
class AggregatedSearchPagingSource(
    private val sources: List<ModelSource>,
    private val query: SearchQuery,
    private val pageSizePerSource: Int,
    private val onWarning: (SourceWarning) -> Unit,
) : PagingSource<AggregateKey, ModelSummary>() {

    override suspend fun load(params: LoadParams<AggregateKey>): LoadResult<AggregateKey, ModelSummary> {
        var key = params.key ?: AggregateKey(sources.associate { it.id to null })
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
        val active = sources.filter { key.cursors.containsKey(it.id) }
        if (active.isEmpty()) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        val results = coroutineScope {
            active.map { src ->
                async {
                    src.id to runCatching {
                        if (!src.isAvailable()) throw SourceException.NotConfigured(src.id)
                        src.search(query, PageRequest(key.cursors[src.id], pageSizePerSource))
                    }
                }
            }.map { it.await() }
        }

        val nextCursors = mutableMapOf<SourceId, String?>()
        val perSource = mutableListOf<List<ModelSummary>>()
        var failures = 0
        for ((id, r) in results) {
            r.onSuccess { page ->
                perSource += page.items.filter { ClientSideFilter.accept(it, query.filters) }
                if (page.nextCursor != null) nextCursors[id] = page.nextCursor
            }.onFailure { e ->
                failures++
                onWarning(SourceWarning(id, e))
            }
        }
        if (failures == results.size) {
            return LoadResult.Error(results.first().second.exceptionOrNull() ?: IllegalStateException("Brak wyników"))
        }
        val merged = interleave(perSource).distinctBy { it.key }
        val nextKey = if (nextCursors.isEmpty()) null else AggregateKey(nextCursors)
        return LoadResult.Page(merged, prevKey = null, nextKey = nextKey)
    }

    override fun getRefreshKey(state: PagingState<AggregateKey, ModelSummary>): AggregateKey? = null

    private companion object {
        const val MAX_EMPTY_PAGE_RETRIES = 3
    }

    private fun interleave(lists: List<List<ModelSummary>>): List<ModelSummary> {
        val out = ArrayList<ModelSummary>(lists.sumOf { it.size })
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }
}
