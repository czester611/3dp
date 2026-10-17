package pl.u1companion.data.source.thingiverse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.u1companion.core.model.Author
import pl.u1companion.core.model.FileKind
import pl.u1companion.core.model.License
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.settings.ApiCredential
import pl.u1companion.data.settings.ApiCredentials
import pl.u1companion.data.source.AccessMode
import pl.u1companion.data.source.DownloadTicket
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SearchPage
import pl.u1companion.data.source.SourceCapabilities
import pl.u1companion.data.source.SourceException
import pl.u1companion.data.source.SortResolution
import pl.u1companion.core.model.Category
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter Thingiverse - oficjalne REST API https://api.thingiverse.com (App Token, nagłówek Bearer).
 * Token użytkownik generuje sam (https://www.thingiverse.com/apps/create) i wpisuje w Ustawieniach.
 *
 * STATUS WERYFIKACJI: ścieżki /search/{term}, /things/{id}, /things/{id}/files, /things/{id}/images,
 * /files/{id} potwierdzone tylko w kodzie zewnętrznych klientów (np. projekt Kiln), NIE w oficjalnej
 * dokumentacji (strona deweloperska zwracała 403 dla automatycznego pobrania). Nazwy pól odpowiedzi
 * traktujemy jako "best effort" - wszystkie DTO mają wartości domyślne. Wymaga testu z prawdziwym tokenem.
 */
@Singleton
class ThingiverseSource @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val credentials: ApiCredentials,
) : ModelSource {

    override val id = SourceId.THINGIVERSE

    override val capabilities = SourceCapabilities(
        access = AccessMode.OFFICIAL_API,
        supportedSorts = setOf(SortOrder.RELEVANCE, SortOrder.POPULAR, SortOrder.NEWEST),
        // Filtr kategorii w /search NIEZWERYFIKOWANY -> kategorie tylko do drzewa w UI, filtr po stronie klienta.
        serverCategoryFilter = false,
        requiresCredential = true,
        inAppDownload = true,
        hasPaidContent = false,
    )

    override suspend fun isAvailable() = credentials.get(id) != null

    /** Adres API (podmieniany w testach kontraktowych na MockWebServer). */
    internal var base = BASE

    override fun webModelUrl(modelId: String) = "https://www.thingiverse.com/thing:$modelId"

    override fun webSearchUrl(text: String) =
        "https://www.thingiverse.com/search?type=things&q=" + URLEncoder.encode(text, "UTF-8")

    override suspend fun search(query: SearchQuery, page: PageRequest): SearchPage {
        val pageNo = page.cursor?.toIntOrNull() ?: 1
        val sort = resolveSort(query.filters.sort, query.text.isNotBlank()).nativeValue
        val term = query.text.ifBlank { "*" }
        val url = "$base/search/${URLEncoder.encode(term, "UTF-8").replace("+", "%20")}".toHttpUrl().newBuilder()
            .addQueryParameter("type", "things")
            .addQueryParameter("page", pageNo.toString())
            .addQueryParameter("per_page", page.size.toString())
            .addQueryParameter("sort", sort)
            .build()
        val res: SearchDto = get(url.toString())
        val items = res.hits.map { it.toSummary() }
        return SearchPage(items, if (items.size < page.size) null else (pageNo + 1).toString(), res.total)
    }

    override suspend fun details(modelId: String): ModelDetail {
        val thing: ThingDto = get("$base/things/$modelId")
        val files: List<FileDto> = runCatching { get<List<FileDto>>("$base/things/$modelId/files") }.getOrDefault(emptyList())
        val images: List<ImageDto> = runCatching { get<List<ImageDto>>("$base/things/$modelId/images") }.getOrDefault(emptyList())
        val modelFiles = files.map {
            ModelFile(it.id.toString(), it.name ?: "plik-${it.id}", it.size, FileKind.fromFileName(it.name.orEmpty()))
        }
        val summary = thing.toSummary().copy(
            hasPrintReadyFile = modelFiles.any { it.kind == FileKind.GCODE },
            hasU1Profile = modelFiles.any { it.isLikelyForU1 },
        )
        return ModelDetail(
            summary = summary,
            descriptionHtml = thing.description_html ?: thing.description,
            images = images.mapNotNull { img -> img.sizes.firstOrNull { it.type == "display" && it.size == "large" }?.url ?: img.url }
                .ifEmpty { listOfNotNull(thing.thumbnail) },
            files = modelFiles,
            tags = thing.tags.mapNotNull { it.name },
        )
    }

    /** Wartości sort wg klientów zewnętrznych: relevant, popular, newest, makes - NIEZWERYFIKOWANE oficjalnie. */
    override fun resolveSort(sort: SortOrder, hasText: Boolean): SortResolution = when (sort) {
        SortOrder.RELEVANCE -> SortResolution(sort, "relevant", "trafność", true)
        SortOrder.POPULAR -> SortResolution(sort, "popular", "popularne", true)
        SortOrder.NEWEST -> SortResolution(sort, "newest", "najnowsze", true)
        SortOrder.TOP_RATED, SortOrder.MOST_LIKED -> SortResolution(sort, "popular", "popularne", false)
        SortOrder.MOST_DOWNLOADED -> SortResolution(sort, "makes", "liczba wydruków (makes)", false)
    }

    /**
     * GET /categories -> [{name, url, count}] (best effort, NIEZWERYFIKOWANE bez tokenu).
     * ID kategorii = slug z URL (np. ".../categories/household" -> "household"). Lista płaska.
     */
    override suspend fun categories(): List<Category> {
        if (!isAvailable()) return emptyList()
        val list: List<CategoryDto> = get("$base/categories")
        return list.mapNotNull { c ->
            val name = c.name ?: return@mapNotNull null
            val slug = c.url?.trimEnd('/')?.substringAfterLast('/') ?: name.lowercase().replace(' ', '-')
            Category(id = slug, name = name, source = SourceId.THINGIVERSE)
        }
    }

    override suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket {
        val token = token()
        // /files/{id}/download przekierowuje do pliku; OkHttp podąża za redirectem.
        return DownloadTicket(
            url = "$base/files/${file.id}/download",
            fileName = file.name,
            headers = mapOf("Authorization" to "Bearer $token"),
        )
    }

    private suspend fun token(): String =
        (credentials.get(id) as? ApiCredential.Token)?.value ?: throw SourceException.NotConfigured(id)

    private suspend inline fun <reified T> get(url: String): T {
        val token = token()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            val body = try {
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 -> throw SourceException.Unauthorized(id)
                        resp.code == 429 -> throw SourceException.RateLimited(id, resp.header("Retry-After")?.toLongOrNull())
                        !resp.isSuccessful -> throw SourceException.Network(id, IOException("HTTP ${resp.code}"))
                    }
                    resp.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw SourceException.Network(id, e)
            }
            try {
                json.decodeFromString<T>(body)
            } catch (e: Exception) {
                throw SourceException.SchemaChanged(id, e)
            }
        }
    }

    private fun ThingDto.toSummary() = ModelSummary(
        source = SourceId.THINGIVERSE,
        id = id.toString(),
        title = name ?: "(bez nazwy)",
        author = creator?.let { Author(it.name ?: "?", it.public_url, it.thumbnail) },
        thumbnailUrl = preview_image ?: thumbnail,
        webUrl = public_url ?: "https://www.thingiverse.com/thing:$id",
        likes = like_count,
        downloads = download_count,
        publishedAtEpochMs = added?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
        license = license?.let { License(it, it, LicenseFamily.fromText(it)) },
        isFree = true,
        category = null,
    )

    @Suppress("PropertyName")
    @Serializable private data class SearchDto(val total: Int? = null, val hits: List<ThingDto> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private data class ThingDto(
        val id: Long,
        val name: String? = null,
        val thumbnail: String? = null,
        val preview_image: String? = null,
        val public_url: String? = null,
        val creator: CreatorDto? = null,
        val like_count: Int? = null,
        val download_count: Int? = null,
        val added: String? = null,
        val license: String? = null,
        val description: String? = null,
        val description_html: String? = null,
        val tags: List<TagDto> = emptyList(),
    )

    @Suppress("PropertyName")
    @Serializable private data class CreatorDto(val name: String? = null, val public_url: String? = null, val thumbnail: String? = null)
    @Serializable private data class TagDto(val name: String? = null)
    @Serializable private data class CategoryDto(val name: String? = null, val url: String? = null, val count: Int? = null)
    @Serializable private data class FileDto(val id: Long, val name: String? = null, val size: Long? = null)
    @Serializable private data class ImageDto(val url: String? = null, val sizes: List<ImageSizeDto> = emptyList())
    @Serializable private data class ImageSizeDto(val type: String? = null, val size: String? = null, val url: String? = null)

    private companion object {
        const val BASE = "https://api.thingiverse.com"
    }
}
