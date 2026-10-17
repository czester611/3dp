package pl.u1companion.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.repo.LibraryRepository
import pl.u1companion.data.repo.ModelRepository
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.SourceRegistry
import pl.u1companion.data.source.SourceWarning
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: ModelRepository,
    private val library: LibraryRepository,
    registry: SourceRegistry,
    @Suppress("unused") savedState: SavedStateHandle,
) : ViewModel() {

    private val _query = MutableStateFlow(SearchQuery())
    val query: StateFlow<SearchQuery> = _query.asStateFlow()

    val searchableSources: List<ModelSource> = registry.searchable()
    val webSources: List<ModelSource> = registry.webAssisted()

    /** Lista z infinite scroll. Tekst debounce 400 ms, zmiana filtrów natychmiast. */
    val results: Flow<PagingData<ModelSummary>> = _query
        .debounce { if (it.text != lastText) 400L else 0L }
        .distinctUntilChanged()
        .flatMapLatest { q -> lastText = q.text; repo.search(q) }
        .cachedIn(viewModelScope)

    private var lastText = ""

    val favoriteKeys: StateFlow<Set<String>> = library.favoriteKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val warnings: Flow<SourceWarning> = repo.warnings

    /** Liczba aktywnych filtrów (badge na przycisku "Filtry"). */
    val activeFilterCount: StateFlow<Int> = _query.map { q ->
        val f = q.filters
        listOf(
            f.categoryId != null, f.licenses.isNotEmpty(), !f.onlyFree, f.onlyPrintReady,
            f.onlyU1Profile, f.onlyFitsU1, f.onlyCommercialUse,
        ).count { it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setText(t: String) = _query.update { it.copy(text = t) }
    fun updateFilters(block: (SearchFilters) -> SearchFilters) = _query.update { it.copy(filters = block(it.filters)) }
    fun setSort(s: SortOrder) = updateFilters { it.copy(sort = s) }

    fun toggleSource(id: SourceId) = updateFilters { f ->
        val next = if (id in f.sources) f.sources - id else f.sources + id
        f.copy(sources = next.ifEmpty { setOf(SourceId.PRINTABLES) })
    }

    fun toggleLicense(l: LicenseFamily) = updateFilters { f ->
        f.copy(licenses = if (l in f.licenses) f.licenses - l else f.licenses + l)
    }

    fun resetFilters() = updateFilters { SearchFilters(sources = it.sources, sort = it.sort) }

    fun applySaved(q: SearchQuery) { _query.value = q }

    fun toggleFavorite(m: ModelSummary) = viewModelScope.launch {
        library.toggleFavorite(m, m.key in favoriteKeys.value)
    }

    fun saveCurrentSearch(name: String) = viewModelScope.launch {
        library.saveSearch(name.ifBlank { _query.value.text.ifBlank { "Wyszukiwanie" } }, _query.value)
    }
}
