package pl.u1companion.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.category.CategoryRepository
import pl.u1companion.data.source.AggregatedSearchPagingSource
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.SourceRegistry
import pl.u1companion.data.source.SourceWarning
import pl.u1companion.data.source.WebFallbackPolicy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Adnotacja do wyników (sortowanie zastąpione, kategoria nieobsługiwana itp.). */
data class SearchNote(val source: SourceId?, val text: String)

@Singleton
class ModelRepository @Inject constructor(
    private val registry: SourceRegistry,
    private val categories: CategoryRepository,
    private val geometry: GeometryRepository,
) {
    private val _warnings = MutableSharedFlow<SourceWarning>(extraBufferCapacity = 16)
    val warnings: SharedFlow<SourceWarning> = _warnings

    /** Prosty cache szczegółów w pamięci (TTL w etapie 3: Room + ETag). */
    private val detailCache = ConcurrentHashMap<String, ModelDetail>()

    /** Wybrane źródła, które da się teraz użyć przez API (bez klucza -> tylko przeglądarka, nie lista wyników). */
    private suspend fun activeSources(query: SearchQuery): List<ModelSource> =
        registry.searchable().filter { it.id in query.filters.sources }
            .filter { s -> WebFallbackPolicy.usableViaApi(s, runCatching { s.isAvailable() }.getOrDefault(false)) || s.capabilities.access == pl.u1companion.data.source.AccessMode.DEMO }

    fun search(query: SearchQuery): Flow<PagingData<ModelSummary>> = flow {
        val plans = categories.plan(activeSources(query), query.filters)
        emitAll(
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false),
                pagingSourceFactory = {
                    AggregatedSearchPagingSource(plans, query, PAGE_SIZE, facts = { geometry.factsByModel.value }) { _warnings.tryEmit(it) }
                },
            ).flow,
        )
    }

    /** Adnotacje dla bieżącego zapytania: zastąpione sortowania + uwagi o kategoriach. */
    suspend fun notes(query: SearchQuery): List<SearchNote> {
        val sources = activeSources(query)
        val sortNotes = sources.mapNotNull { s ->
            val r = s.resolveSort(query.filters.sort, query.text.isNotBlank())
            if (r.exact) null else SearchNote(s.id, "${s.id.displayName}: sortowanie „${query.filters.sort.label}” niedostępne — użyto: ${r.nativeLabel}")
        }
        val catNotes = categories.plan(sources, query.filters).flatMap { p -> p.notes.map { SearchNote(p.source.id, it) } }
        val activeIds = sources.map { it.id }.toSet()
        val keyNotes = registry.searchable().filter { it.id in query.filters.sources && it.id !in activeIds }.map {
            SearchNote(it.id, "${it.id.displayName}: brak klucza API — wyniki niedostępne w liście; otwórz serwis w przeglądarce (↗) lub ustaw klucz w Ustawieniach")
        }
        return keyNotes + sortNotes + catNotes
    }

    suspend fun details(source: SourceId, modelId: String): ModelDetail {
        val key = "${source.name}:$modelId"
        detailCache[key]?.let { return it }
        return registry[source].details(modelId).also { detailCache[key] = it }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
