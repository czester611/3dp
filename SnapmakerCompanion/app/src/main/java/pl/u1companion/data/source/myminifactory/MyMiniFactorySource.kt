package pl.u1companion.data.source.myminifactory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.u1companion.core.model.Author
import pl.u1companion.core.model.Category
import pl.u1companion.core.model.Dimensions
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
import pl.u1companion.data.source.SortResolution
import pl.u1companion.data.source.SourceCapabilities
import pl.u1companion.data.source.SourceException
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MyMiniFactory - oficjalne API v2 (https://www.myminifactory.com/api/v2), klucz aplikacji w parametrze `key`.
 *
 * Źródło specyfikacji: github.com/MyMiniFactory/api-documentation (myminifactory-api.yaml, OpenAPI 3).
 *  - GET /search?q&page&per_page&sort(visits|date|popularity)&order(desc|asc)&cat&commercial_use&remix...
 *    -> {total_count, items: [Object]}
 *  - GET /objects/{id}, GET /objects/{id}/files -> {total_count, items: [File]}
 *  - GET /categories -> {total_count, items: [Category{id, slug, name, children{items}, parent}]}
 *  - `download_url` / `archive_download_url` są dostępne TYLKO dla użytkownika zalogowanego OAuth2,
 *    NIE z kluczem API -> pobieranie przez stronę w WebView (użytkownik loguje się sam).
 *
 * NIE testowano z prawdziwym kluczem (tylko testy kontraktowe na odpowiedziach zgodnych ze specyfikacją).
 * Brak w specyfikacji: pola ceny (płatne = licencja typu "store"), zachowanie q="" (puste zapytanie).
 */
@Singleton
class MyMiniFactorySource @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val credentials: ApiCredentials,
) : ModelSource {

    override val id = SourceId.MYMINIFACTORY

    override val capabilities = SourceCapabilities(
        access = AccessMode.OFFICIAL_API,
        supportedSorts = setOf(SortOrder.RELEVANCE, SortOrder.POPULAR, SortOrder.NEWEST),
        serverCategoryFilter = true,   // parametr `cat`
        serverLicenseFilter = false,   // tylko commercial_use/remix - mapujemy "komercyjnie" po stronie serwera
        requiresCredential = true,
        inAppDownload = false,         // download_url wymaga OAuth użytkownika
        hasPaidContent = true,
    )

    internal var base = BASE

    override suspend fun isAvailable() = credentials.get(id) != null

    override fun webModelUrl(modelId: String) = "https://www.myminifactory.com/object/$modelId"

    override fun webSearchUrl(text: String) = "https://www.myminifactory.com/search/?query=" + URLEncoder.encode(text, "UTF-8")

    override fun resolveSort(sort: SortOrder, hasText: Boolean): SortResolution = when (sort) {
        SortOrder.RELEVANCE -> if (hasText) SortResolution(sort, "", "trafność (domyślna)", true)
        else SortResolution(sort, "popularity", "popularne (trafność wymaga frazy)", false)
        SortOrder.POPULAR -> SortResolution(sort, "popularity", "popularne", true)
        SortOrder.NEWEST -> SortResolution(sort, "date", "najnowsze", true)
        SortOrder.MOST_DOWNLOADED -> SortResolution(sort, "visits", "wyświetlenia", false)
        SortOrder.MOST_LIKED, SortOrder.TOP_RATED -> SortResolution(sort, "popularity", "popularne", false)
    }

    override suspend fun search(query: SearchQuery, page: PageRequest): SearchPage {
        val pageNo = page.cursor?.toIntOrNull() ?: 1
        val sort = resolveSort(query.filters.sort, query.text.isNotBlank()).nativeValue
        val url = url("search") {
            addQueryParameter("q", query.text)
            addQueryParameter("page", pageNo.toString())
            addQueryParameter("per_page", page.size.toString())
            if (sort.isNotEmpty()) { addQueryParameter("sort", sort); addQueryParameter("order", "desc") }
            page.categoryId?.let { addQueryParameter("cat", it) }
            if (query.filters.onlyCommercialUse) addQueryParameter("commercial_use", "1")
        }
        val res: ListDto<ObjectDto> = get(url)
        val items = res.items.map { it.toSummary() }
        val more = res.total_count?.let { pageNo * page.size < it } ?: (res.items.size >= page.size)
        return SearchPage(items, if (more && items.isNotEmpty()) (pageNo + 1).toString() else null, res.total_count)
    }

    override suspend fun details(modelId: String): ModelDetail {
        val o: ObjectDto = get(url("objects/$modelId") {})
        val files = o.files.ifEmpty {
            runCatching { get<ListDto<FileDto>>(url("objects/$modelId/files") { addQueryParameter("per_page", "100") }).items }.getOrDefault(emptyList())
        }.map { f -> ModelFile(f.id.toString(), f.filename ?: "plik-${f.id}", f.size?.toLongOrNull(), FileKind.fromFileName(f.filename.orEmpty())) }
        return ModelDetail(
            summary = o.toSummary().copy(
                hasPrintReadyFile = files.any { it.kind == FileKind.GCODE || it.kind == FileKind.THREE_MF },
                hasU1Profile = files.any { it.isLikelyForU1 },
            ),
            descriptionHtml = listOfNotNull(o.description_html ?: o.description, o.printing_details_html?.let { "<h3>Druk</h3>$it" }).joinToString("\n"),
            images = o.images.sortedByDescending { it.is_primary == true }.mapNotNull { it.standard?.url ?: it.original?.url ?: it.thumbnail?.url },
            files = files,
            tags = o.tags,
            dimensions = parseDimensions(o.dimensions),
        )
    }

    override suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket =
        throw SourceException.Unsupported(id, "pobieranie z kluczem API (wymaga logowania OAuth) - użyj strony w przeglądarce")

    override suspend fun categories(): List<Category> {
        if (!isAvailable()) return emptyList()
        val res: ListDto<CategoryDto> = get(url("categories") { addQueryParameter("per_page", "100") })
        return flattenCategories(res.items)
    }

    // --- mapowanie ---

    internal fun flattenCategories(items: List<CategoryDto>): List<Category> {
        val out = LinkedHashMap<String, Category>()
        items.forEach { c ->
            val cid = c.id.toString()
            out.putIfAbsent(cid, Category(cid, c.name ?: c.slug ?: cid, c.parent?.id?.toString(), SourceId.MYMINIFACTORY))
            c.children?.items.orEmpty().forEach { ch ->
                val chId = ch.id.toString()
                out.putIfAbsent(chId, Category(chId, ch.name ?: ch.slug ?: chId, cid, SourceId.MYMINIFACTORY))
            }
        }
        return out.values.toList()
    }

    private fun ObjectDto.toSummary(): ModelSummary {
        val flags = licenses.associate { (it.type ?: "") to (it.value == true) }
        val isStore = flags["store"] == true
        return ModelSummary(
            source = SourceId.MYMINIFACTORY,
            id = id.toString(),
            title = name ?: "(bez nazwy)",
            author = designer?.let { Author(it.name ?: it.username ?: "?", it.profile_url, it.avatar_thumbnail_url ?: it.avatar_url) },
            thumbnailUrl = images.firstOrNull { it.is_primary == true }?.let { it.thumbnail?.url ?: it.standard?.url }
                ?: images.firstOrNull()?.thumbnail?.url,
            webUrl = url ?: "https://www.myminifactory.com/object/$id",
            likes = likes,
            downloads = null, // API nie zwraca liczby pobrań (są tylko views)
            publishedAtEpochMs = published_at?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            license = License(if (isStore) "store" else null, license ?: licenseLabel(flags), licenseFamily(license, flags)),
            isFree = !isStore,
            category = categories.firstOrNull()?.name,
            hasPrintReadyFile = null,
            hasU1Profile = null,
        )
    }

    private fun licenseLabel(flags: Map<String, Boolean>) = buildList {
        if (flags["store"] == true) add("Store")
        if (flags["commercial-use"] == true) add("komercyjnie") else add("niekomercyjnie")
        if (flags["remix"] == true) add("remiks")
    }.joinToString(", ", prefix = "MMF: ")

    /**
     * MMF ma licencję opisaną flagami (mention/remix/commercial-use/share/store). Jeśli tekst `license`
     * zawiera nazwę Creative Commons - używamy go, w przeciwnym razie przybliżamy rodziną CC z flag.
     */
    internal fun licenseFamily(text: String?, flags: Map<String, Boolean>): LicenseFamily {
        val fromText = LicenseFamily.fromText(text)
        if (text != null && (text.contains("Creative Commons", true) || text.contains("CC", false) || text.contains("GPL", true))) return fromText
        val commercial = flags["commercial-use"] == true
        val remix = flags["remix"] == true
        return when {
            flags["store"] == true -> LicenseFamily.STANDARD_DIGITAL
            flags.isEmpty() -> LicenseFamily.UNKNOWN
            commercial && remix -> LicenseFamily.CC_BY
            commercial -> LicenseFamily.CC_BY_ND
            remix -> LicenseFamily.CC_BY_NC
            else -> LicenseFamily.CC_BY_NC_ND
        }
    }

    // --- HTTP ---

    private fun url(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        "$base/$path".toHttpUrl().newBuilder().apply(block).build()

    private suspend inline fun <reified T> get(url: HttpUrl): T {
        val key = (credentials.get(id) as? ApiCredential.Token)?.value ?: throw SourceException.NotConfigured(id)
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url.newBuilder().addQueryParameter("key", key).build()).header("Accept", "application/json").build()
            val body = try {
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 401 || r.code == 403 -> throw SourceException.Unauthorized(id)
                        r.code == 429 -> throw SourceException.RateLimited(id, r.header("Retry-After")?.toLongOrNull())
                        !r.isSuccessful -> throw SourceException.Network(id, IOException("HTTP ${r.code}"))
                    }
                    r.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw SourceException.Network(id, e)
            }
            try { json.decodeFromString<T>(body) } catch (e: Exception) { throw SourceException.SchemaChanged(id, e) }
        }
    }

    // --- DTO (nazwy pól wg myminifactory-api.yaml) ---

    @Suppress("PropertyName")
    @Serializable internal data class ListDto<T>(val total_count: Int? = null, val items: List<T> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    internal data class ObjectDto(
        val id: Long,
        val url: String? = null,
        val name: String? = null,
        val description: String? = null,
        val description_html: String? = null,
        val printing_details_html: String? = null,
        val views: Int? = null,
        val likes: Int? = null,
        val published_at: String? = null,
        val dimensions: String? = null,
        val designer: UserDto? = null,
        val images: List<ImageDto> = emptyList(),
        val files: List<FileDto> = emptyList(),
        val categories: List<OneCategoryDto> = emptyList(),
        val tags: List<String> = emptyList(),
        val licenses: List<LicenseDto> = emptyList(),
        val license: String? = null,
    )

    @Suppress("PropertyName")
    @Serializable internal data class UserDto(val username: String? = null, val name: String? = null, val profile_url: String? = null, val avatar_url: String? = null, val avatar_thumbnail_url: String? = null)
    @Suppress("PropertyName")
    @Serializable internal data class ImageDto(val is_primary: Boolean? = null, val original: UrlDto? = null, val thumbnail: UrlDto? = null, val standard: UrlDto? = null)
    @Serializable internal data class UrlDto(val url: String? = null)
    @Serializable internal data class FileDto(val id: Long, val filename: String? = null, val size: String? = null)
    @Serializable internal data class LicenseDto(val type: String? = null, val value: Boolean? = null)
    @Serializable internal data class OneCategoryDto(val id: Long? = null, val name: String? = null)
    @Serializable internal data class CategoryRefDto(val id: Long, val slug: String? = null, val name: String? = null)
    @Serializable internal data class ChildrenDto(val items: List<CategoryRefDto> = emptyList())
    @Serializable internal data class CategoryDto(val id: Long, val slug: String? = null, val name: String? = null, val children: ChildrenDto? = null, val parent: CategoryRefDto? = null)

    companion object {
        const val BASE = "https://www.myminifactory.com/api/v2"

        /** Pole `dimensions` to wolny tekst (np. "120 x 80 x 40 mm") - bierzemy 3 pierwsze liczby (mm; "cm" -> ×10). */
        fun parseDimensions(text: String?): Dimensions? {
            if (text.isNullOrBlank()) return null
            val n = Regex("(\\d+(?:[.,]\\d+)?)").findAll(text).map { it.value.replace(',', '.').toFloat() }.take(3).toList()
            if (n.size < 3) return null
            val scale = if (Regex("\\bcm\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) 10f else 1f
            return Dimensions(n[0] * scale, n[1] * scale, n[2] * scale)
        }
    }
}
