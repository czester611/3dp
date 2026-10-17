package pl.u1companion.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.source.AggregatedSearchPagingSource
import pl.u1companion.data.source.SourceRegistry
import pl.u1companion.data.source.SourceWarning
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val registry: SourceRegistry,
) {
    private val _warnings = MutableSharedFlow<SourceWarning>(extraBufferCapacity = 16)
    val warnings: SharedFlow<SourceWarning> = _warnings

    /** Prosty cache szczegółów w pamięci (TTL w etapie 2: Room + ETag). */
    private val detailCache = ConcurrentHashMap<String, ModelDetail>()

    fun search(query: SearchQuery): Flow<PagingData<ModelSummary>> {
        val sources = registry.searchable().filter { it.id in query.filters.sources }
        return Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false),
            pagingSourceFactory = {
                AggregatedSearchPagingSource(sources, query, PAGE_SIZE) { _warnings.tryEmit(it) }
            },
        ).flow
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
