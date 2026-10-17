package pl.u1companion.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.data.local.AppDatabase
import pl.u1companion.data.local.FavoriteEntity
import pl.u1companion.data.local.HistoryEntity
import pl.u1companion.data.local.HistoryType
import pl.u1companion.data.local.SavedSearchEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Ulubione, historia, zapisane wyszukiwania. */
@Singleton
class LibraryRepository @Inject constructor(
    private val db: AppDatabase,
    private val json: Json,
) {
    val favorites: Flow<List<FavoriteEntity>> = db.favorites().observeAll()
    val favoriteKeys: Flow<Set<String>> = db.favorites().observeKeys().map { it.toSet() }
    val history: Flow<List<HistoryEntity>> = db.history().observeRecent()
    val savedSearches: Flow<List<SavedSearchEntity>> = db.savedSearches().observeAll()

    suspend fun toggleFavorite(m: ModelSummary, isFavorite: Boolean) {
        if (isFavorite) db.favorites().delete(m.key)
        else db.favorites().upsert(
            FavoriteEntity(
                key = m.key, source = m.source.name, modelId = m.id, title = m.title,
                thumbnailUrl = m.thumbnailUrl, author = m.author?.name,
                licenseFamily = m.license?.family?.name, webUrl = m.webUrl,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recordHistory(type: HistoryType, m: ModelSummary?, title: String = m?.title.orEmpty(), detail: String? = null) {
        db.history().insert(
            HistoryEntity(type = type, modelKey = m?.key, title = title, thumbnailUrl = m?.thumbnailUrl, detail = detail, at = System.currentTimeMillis()),
        )
    }

    suspend fun clearHistory() = db.history().clear()

    suspend fun saveSearch(name: String, query: SearchQuery): Long =
        db.savedSearches().insert(
            SavedSearchEntity(name = name, queryJson = json.encodeToString(SearchQuery.serializer(), query), createdAt = System.currentTimeMillis()),
        )

    fun decodeSearch(e: SavedSearchEntity): SearchQuery =
        runCatching { json.decodeFromString(SearchQuery.serializer(), e.queryJson) }.getOrDefault(SearchQuery())

    suspend fun markSearchRun(id: Long) = db.savedSearches().touch(id, System.currentTimeMillis())

    suspend fun deleteSearch(id: Long) = db.savedSearches().delete(id)
}
