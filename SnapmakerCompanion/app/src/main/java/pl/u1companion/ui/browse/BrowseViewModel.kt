package pl.u1companion.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.u1companion.core.model.CanonicalCategory
import pl.u1companion.core.model.Category
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceCategoryRef
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.category.CategoryMapper
import pl.u1companion.data.category.CategoryRepository
import pl.u1companion.data.repo.GeometryRepository
import pl.u1companion.data.repo.LibraryRepository
import pl.u1companion.data.repo.ModelRepository
import pl.u1companion.data.repo.SearchNote
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.SourceRegistry
import pl.u1companion.data.source.SourceWarning
import pl.u1companion.geometry.ModelFacts
import javax.inject.Inject

/** Drzewo kategorii jednego serwisu dla arkusza filtrów. */
data class SourceCategoryTree(val source: SourceId, val roots: List<Pair<Category, List<Category>>>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: ModelRepository,
    private val library: LibraryRepository,
    private val categoryRepo: CategoryRepository,
    geometry: GeometryRepository,
    registry: SourceRegistry,
) : ViewModel() {

    private val _query = MutableStateFlow(SearchQuery())
    val query: StateFlow<SearchQuery> = _query.asStateFlow()

    val searchableSources: List<ModelSource> = registry.searchable()
    /** Serwisy do otwarcia w wbudowanej przeglądarce (bez API lub jako alternatywa dla API z kluczem). */
    val webSources: List<ModelSource> = registry.allSources().filter { it.capabilities.access != pl.u1companion.data.source.AccessMode.DEMO }

    /** Czy źródło jest skonfigurowane (np. jest klucz API) - oznaczenie na chipach. */
    private val _availability = MutableStateFlow<Map<SourceId, Boolean>>(emptyMap())
    val availability: StateFlow<Map<SourceId, Boolean>> = _availability

    /** Źródła już raz automatycznie włączone - użytkownik może je potem wyłączyć chipem i nie wracają same. */
    private val autoEnabled = mutableSetOf<SourceId>()

    fun refreshAvailability() = viewModelScope.launch {
        val avail = searchableSources.associate { it.id to runCatching { it.isAvailable() }.getOrDefault(false) }
        _availability.value = avail
        // Źródła z kluczem API (Thingiverse, MyMiniFactory, Cults3D) działają normalnie: włączamy je, gdy klucz jest ustawiony.
        val toEnable = searchableSources.filter { it.capabilities.requiresCredential && avail[it.id] == true && it.id !in autoEnabled }.map { it.id }
        if (toEnable.isNotEmpty()) {
            autoEnabled += toEnable
            updateFilters { f -> f.copy(sources = f.sources + toEnable) }
        }
    }

    private var lastText = ""

    private val debounced: Flow<SearchQuery> = _query
        .debounce { if (it.text != lastText) 400L else 0L }
        .distinctUntilChanged()

    /** Lista z infinite scroll. Tekst debounce 400 ms, zmiana filtrów natychmiast. */
    val results: Flow<PagingData<ModelSummary>> = debounced
        .flatMapLatest { q -> lastText = q.text; repo.search(q) }
        .cachedIn(viewModelScope)

    /** Adnotacje: sortowanie zastąpione odpowiednikiem, kategoria nieobsługiwana przez serwis. */
    val notes: StateFlow<List<SearchNote>> = debounced
        .mapLatest { runCatching { repo.notes(it) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = library.favoriteKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Fakty z analizy pobranych plików (wymiary/profil U1) - ikony "✓/✗/?" na kartach. */
    val facts: StateFlow<Map<String, ModelFacts>> = geometry.factsByModel

    val warnings: Flow<SourceWarning> = repo.warnings

    private val _categoryTrees = MutableStateFlow<List<SourceCategoryTree>>(emptyList())
    val categoryTrees: StateFlow<List<SourceCategoryTree>> = _categoryTrees

    /** Liczba aktywnych filtrów (badge na przycisku "Filtry"). */
    val activeFilterCount: StateFlow<Int> = _query.map { q ->
        val f = q.filters
        listOf(
            f.category != null || f.sourceCategory != null, f.licenses.isNotEmpty(), !f.onlyFree, f.onlyPrintReady,
            f.onlyU1Profile, f.onlyFitsU1, f.onlyCommercialUse,
        ).count { it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Ładuje drzewa kategorii wybranych źródeł (przy otwarciu arkusza filtrów). */
    fun loadCategories() = viewModelScope.launch {
        _categoryTrees.value = _query.value.filters.sources.sorted().mapNotNull { s ->
            val cats = runCatching { categoryRepo.categories(s) }.getOrDefault(emptyList())
            if (cats.isEmpty()) null else SourceCategoryTree(s, CategoryMapper.tree(cats))
        }
    }

    fun setText(t: String) = _query.update { it.copy(text = t) }
    fun updateFilters(block: (SearchFilters) -> SearchFilters) = _query.update { it.copy(filters = block(it.filters)) }
    fun setSort(s: SortOrder) = updateFilters { it.copy(sort = s) }

    /** Kategoria wspólna dla wszystkich serwisów (null = wszystkie). */
    fun setCategory(c: CanonicalCategory?) = updateFilters { it.copy(category = c, sourceCategory = null) }

    /** Podkategoria natywna; dla pozostałych serwisów stosujemy jej kategorię kanoniczną. */
    fun setSourceCategory(source: SourceId, c: Category) = updateFilters {
        it.copy(sourceCategory = SourceCategoryRef(source, c.id, c.name), category = c.canonical)
    }

    fun toggleSource(id: SourceId) = updateFilters { f ->
        val next = if (id in f.sources) f.sources - id else f.sources + id
        f.copy(sources = next.ifEmpty { setOf(SourceId.PRINTABLES) })
    }.also { loadCategories() }

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
