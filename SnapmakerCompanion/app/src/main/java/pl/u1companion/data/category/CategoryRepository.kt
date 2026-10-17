package pl.u1companion.data.category

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import pl.u1companion.core.model.CanonicalCategory
import pl.u1companion.core.model.Category
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.local.AppDatabase
import pl.u1companion.data.local.CategoryCacheEntity
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.SourceRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Plan zapytań dla jednego źródła: lista natywnych kategorii (null = bez filtra) + adnotacje dla UI. */
data class SourcePlan(
    val source: ModelSource,
    val nativeCategoryIds: List<String?>,
    /** Kategoria kanoniczna do filtrowania po stronie klienta (gdy serwer nie filtruje). */
    val clientCategory: CanonicalCategory?,
    val notes: List<String>,
    /** Kategorie natywne źródła (z adnotacją) - do mapowania nazwy kategorii wyniku przy filtrze klienckim. */
    val categories: List<Category> = emptyList(),
)

/**
 * Kategorie natywne serwisów: pobieranie, cache (pamięć + Room, TTL 7 dni), mapowanie na kanoniczne
 * i rozwiązywanie filtra kategorii na zapytania do adapterów.
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val registry: SourceRegistry,
    private val db: AppDatabase,
    private val json: Json,
) {
    private val memory = ConcurrentHashMap<SourceId, List<Category>>()
    private val mutex = Mutex()
    private val serializer = ListSerializer(Category.serializer())

    /** Kategorie z adnotacją kanoniczną; pusta lista gdy serwis nie ma kategorii lub błąd (nie przerywa wyszukiwania). */
    suspend fun categories(source: SourceId, forceRefresh: Boolean = false): List<Category> = mutex.withLock {
        if (!forceRefresh) memory[source]?.let { return it }
        val cached = db.categoryCache().get(source.name)
        val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL_MS
        if (cached != null && fresh && !forceRefresh) {
            runCatching { json.decodeFromString(serializer, cached.json) }.getOrNull()?.let {
                memory[source] = it; return it
            }
        }
        val loaded = runCatching { CategoryMapper.annotate(registry[source].categories()) }.getOrNull()
        val result = when {
            loaded != null && loaded.isNotEmpty() -> {
                db.categoryCache().upsert(CategoryCacheEntity(source.name, json.encodeToString(serializer, loaded), System.currentTimeMillis()))
                loaded
            }
            // Błąd sieci -> użyj przeterminowanego cache, jeśli jest.
            cached != null -> runCatching { json.decodeFromString(serializer, cached.json) }.getOrDefault(emptyList())
            else -> emptyList()
        }
        if (result.isNotEmpty()) memory[source] = result
        result
    }

    /** Rozwiązuje filtr kategorii dla każdego źródła. */
    suspend fun plan(sources: List<ModelSource>, f: SearchFilters): List<SourcePlan> = sources.map { src ->
        planFor(src, f, if (f.category != null || f.sourceCategory != null) categories(src.id) else emptyList())
    }

    companion object {
        const val TTL_MS = 7L * 24 * 3600 * 1000
        /** Maks. liczba równoległych strumieni na źródło, gdy kategoria kanoniczna = kilka kategorii natywnych. */
        const val MAX_STREAMS_PER_SOURCE = 3

        /** Czysta logika planowania (testowalna bez sieci/Room). */
        fun planFor(src: ModelSource, f: SearchFilters, annotated: List<Category>): SourcePlan {
            val name = src.id.displayName
            val explicit = f.sourceCategory?.takeIf { it.source == src.id }
            val canonical = f.category
            if (explicit == null && canonical == null) return SourcePlan(src, listOf(null), null, emptyList())

            if (!src.capabilities.serverCategoryFilter) {
                val note = if (annotated.isEmpty()) "$name: brak kategorii w API — filtr kategorii po nazwie kategorii wyniku (jeśli znana)"
                else "$name: API nie filtruje po kategorii — filtrowanie po stronie aplikacji"
                return SourcePlan(src, listOf(null), canonical, listOf(note), annotated)
            }
            if (explicit != null) return SourcePlan(src, listOf(explicit.id), null, emptyList())

            val natives = CategoryMapper.nativeFor(canonical!!, annotated)
            return when {
                natives.isEmpty() -> SourcePlan(src, listOf(null), canonical, listOf("$name: brak odpowiednika kategorii „${canonical.label}” — filtrowanie po stronie aplikacji"), annotated)
                natives.size > MAX_STREAMS_PER_SOURCE -> SourcePlan(
                    src, natives.take(MAX_STREAMS_PER_SOURCE).map { it.id }, null,
                    listOf("$name: „${canonical.label}” = ${natives.size} kategorii, użyto ${MAX_STREAMS_PER_SOURCE}: " + natives.take(MAX_STREAMS_PER_SOURCE).joinToString { it.name }),
                )
                else -> SourcePlan(src, natives.map { it.id }, null, emptyList())
            }
        }
    }
}
