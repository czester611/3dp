package pl.u1companion.data.source.cults3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.u1companion.core.model.Author
import pl.u1companion.core.model.Category
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
import pl.u1companion.data.source.TtlCache
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cults3D - oficjalne API GraphQL https://cults3d.com/graphql, Basic auth (login:klucz z cults3d.com/en/api/keys).
 *
 * Źródła (oficjalna dokumentacja zwraca 403 dla automatów): społecznościowe notatki
 * github.com/CheekyCodexConjurer/cults3d-api-docs (gisty Cults + kanał Discord #api-help) i klient Kiln.
 *  - `creationsSearchBatch(query, limit, offset) { total results {...} }` - wyszukiwanie (bez sortowania/filtrów w znanych źródłach),
 *  - `creationsBatch(sort: BY_LIKES|BY_DOWNLOADS|..., limit, offset, onlyFree)` - przeglądanie bez frazy,
 *  - `creation(slug)` - szczegóły (w starszych klientach `identifier`), `categories { id name(locale: EN) children {...} }`.
 *  - Limity (zgłoszenia społeczności): ~60 zapytań / 30 s i ~500 / dzień -> cache, oszczędne stronicowanie.
 *  - `blueprints { fileUrl }` - pobranie plików darmowych wymaga sesji przeglądarki -> pobieranie przez WebView.
 *
 * NIE testowano z prawdziwym kluczem. Do weryfikacji w GraphiQL: wartość sortu "najnowsze" (BY_PUBLICATION?),
 * argumenty kategorii w zapytaniach, nazwa argumentu szczegółów (slug vs identifier).
 */
@Singleton
class Cults3dSource @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val credentials: ApiCredentials,
) : ModelSource {

    override val id = SourceId.CULTS3D

    override val capabilities = SourceCapabilities(
        access = AccessMode.OFFICIAL_API,
        supportedSorts = setOf(SortOrder.RELEVANCE, SortOrder.MOST_LIKED, SortOrder.MOST_DOWNLOADED),
        serverCategoryFilter = false, // argument kategorii w creationsBatch niepotwierdzony
        requiresCredential = true,
        inAppDownload = false,
        hasPaidContent = true,
    )

    internal var endpoint = ENDPOINT

    /**
     * Cache wyników na 1 h (limit Cults3D ~500 zapytań/dzień). Klucz = pełne zapytanie (fraza + filtry)
     * + strona (kursor, rozmiar, kategoria). Błędy nie są cache'owane.
     */
    internal var searchCache = TtlCache<String, SearchPage>(CACHE_TTL_MS)
    internal var detailCache = TtlCache<String, ModelDetail>(CACHE_TTL_MS)

    override suspend fun isAvailable() = credentials.get(id) != null

    /** URL modelu Cults3D zawiera kategorię, której nie znamy z samego sluga -> wyszukiwanie po nazwie ze sluga. */
    override fun webModelUrl(modelId: String) = webSearchUrl(modelId.replace('-', ' '))

    override fun webSearchUrl(text: String) = "https://cults3d.com/en/search?q=" + URLEncoder.encode(text, "UTF-8")

    override fun resolveSort(sort: SortOrder, hasText: Boolean): SortResolution =
        if (hasText) {
            // creationsSearchBatch nie ma (znanego) argumentu sortowania.
            SortResolution(sort, "", "trafność (wyszukiwarka Cults3D nie sortuje)", sort == SortOrder.RELEVANCE)
        } else when (sort) {
            SortOrder.MOST_LIKED -> SortResolution(sort, "BY_LIKES", "polubienia", true)
            SortOrder.MOST_DOWNLOADED -> SortResolution(sort, "BY_DOWNLOADS", "pobrania", true)
            SortOrder.NEWEST -> SortResolution(sort, "BY_PUBLICATION", "data publikacji (niezweryfikowane)", false)
            SortOrder.POPULAR, SortOrder.TOP_RATED, SortOrder.RELEVANCE -> SortResolution(sort, "BY_LIKES", "polubienia", false)
        }

    override suspend fun search(query: SearchQuery, page: PageRequest): SearchPage {
        // Mniejsze strony = mniej zbędnych pobrań przy przewijaniu (oszczędzanie limitu dziennego).
        val size = page.size.coerceAtMost(PAGE_SIZE)
        val key = cacheKey(query, page.copy(size = size))
        return searchCache.getOrLoad(key) { searchRemote(query, page.cursor?.toIntOrNull() ?: 0, size) }
    }

    internal fun cacheKey(query: SearchQuery, page: PageRequest): String =
        json.encodeToString(SearchQuery.serializer(), query) + "|${page.cursor}|${page.size}|${page.categoryId}"

    private suspend fun searchRemote(query: SearchQuery, offset: Int, size: Int): SearchPage {
        val page = PageRequest(offset.toString(), size)
        val batch = if (query.text.isNotBlank()) {
            graphql<SearchData>(SEARCH_QUERY, buildJsonObject {
                put("query", query.text); put("limit", page.size); put("offset", offset)
            }).creationsSearchBatch
        } else {
            // Sort jako literał enum (nazwa typu enum w schemacie nieznana, więc nie przez zmienną).
            val sort = resolveSort(query.filters.sort, false).nativeValue.takeIf { it in ALLOWED_SORTS } ?: "BY_LIKES"
            graphql<BrowseData>(browseQuery(sort), buildJsonObject {
                put("limit", page.size); put("offset", offset); put("onlyFree", query.filters.onlyFree)
            }).creationsBatch
        } ?: throw SourceException.SchemaChanged(id, null)
        val next = offset + batch.results.size
        val more = batch.results.isNotEmpty() && (batch.total?.let { next < it } ?: (batch.results.size >= page.size))
        return SearchPage(batch.results.map { it.toSummary() }, if (more) next.toString() else null, batch.total)
    }

    /** modelId = slug (z URL). */
    override suspend fun details(modelId: String): ModelDetail = detailCache.getOrLoad(modelId) { detailsRemote(modelId) }

    private suspend fun detailsRemote(modelId: String): ModelDetail {
        val c = graphql<DetailData>(DETAIL_QUERY, buildJsonObject { put("slug", modelId) }).creation
            ?: throw SourceException.SchemaChanged(id, null)
        val files = c.blueprints.mapIndexed { i, b ->
            val name = b.fileUrl?.substringAfterLast('/')?.substringBefore('?') ?: "plik-${i + 1}"
            ModelFile("$i", name, null, FileKind.fromFileName(name))
        }
        return ModelDetail(
            summary = c.toSummary().copy(
                hasPrintReadyFile = files.any { it.kind == FileKind.GCODE || it.kind == FileKind.THREE_MF },
                hasU1Profile = files.any { it.isLikelyForU1 },
            ),
            descriptionHtml = c.description,
            images = c.illustrations.mapNotNull { it.imageUrl }.ifEmpty { listOfNotNull(c.illustrationImageUrl) },
            files = files,
            tags = c.tags,
        )
    }

    override suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket =
        throw SourceException.Unsupported(id, "pobieranie przez API (wymaga sesji w serwisie) - użyj strony w przeglądarce")

    override suspend fun categories(): List<Category> {
        if (!isAvailable()) return emptyList()
        return flattenCategories(graphql<CategoriesData>(CATEGORIES_QUERY, buildJsonObject { }).categories)
    }

    internal fun flattenCategories(list: List<CategoryDto>): List<Category> = list.flatMap { c ->
        listOf(Category(c.id, c.name ?: c.id, null, SourceId.CULTS3D)) +
            c.children.map { ch -> Category(ch.id, ch.name ?: ch.id, c.id, SourceId.CULTS3D) }
    }

    private fun CreationDto.toSummary(): ModelSummary {
        val slug = (url ?: shortUrl)?.trimEnd('/')?.substringAfterLast('/') ?: identifier ?: "?"
        val cents = price?.cents ?: price?.value?.let { (it * 100).toInt() }
        val lic = license?.let { l ->
            val text = l.spdxId ?: l.code ?: l.name
            License(l.spdxId ?: l.code, l.name ?: text ?: "?", cultsLicenseFamily(l.code, l.spdxId, l.name))
        }
        return ModelSummary(
            source = SourceId.CULTS3D,
            id = slug,
            title = name ?: "(bez nazwy)",
            author = creator?.nick?.let { Author(it, "https://cults3d.com/en/users/$it") },
            thumbnailUrl = illustrationImageUrl,
            webUrl = url ?: shortUrl ?: "https://cults3d.com",
            likes = likesCount,
            downloads = downloadsCount,
            publishedAtEpochMs = publishedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            license = lic,
            isFree = (cents ?: 0) == 0,
            priceCents = cents?.takeIf { it > 0 },
            category = category?.name,
        )
    }

    // --- GraphQL ---

    private suspend inline fun <reified T> graphql(query: String, variables: JsonObject): T {
        val cred = credentials.get(id) as? ApiCredential.UserKey ?: throw SourceException.NotConfigured(id)
        val auth = "Basic " + java.util.Base64.getEncoder().encodeToString("${cred.user}:${cred.key}".toByteArray())
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("query", query); put("variables", variables) }.toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(endpoint).post(body).header("Authorization", auth).build()
            val raw = try {
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 401 || r.code == 403 -> throw SourceException.Unauthorized(id)
                        r.code == 429 -> throw SourceException.RateLimited(
                            id,
                            r.header("Retry-After")?.toLongOrNull() ?: r.header("x-ratelimit-reset")?.toLongOrNull()?.let { reset ->
                                // nagłówek bywa liczbą sekund albo znacznikiem czasu Unix - obsługujemy oba
                                if (reset > 1_000_000_000L) (reset - System.currentTimeMillis() / 1000).coerceAtLeast(1) else reset
                            },
                            "Cults3D pozwala na ok. 60 zapytań/30 s i ok. 500 dziennie; wyniki są cache'owane na 1 h",
                        )
                        !r.isSuccessful -> throw SourceException.Network(id, IOException("HTTP ${r.code}"))
                    }
                    r.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw SourceException.Network(id, e)
            }
            val env = try { json.decodeFromString<Envelope<T>>(raw) } catch (e: Exception) { throw SourceException.SchemaChanged(id, e) }
            if (!env.errors.isNullOrEmpty() || env.data == null) {
                throw SourceException.SchemaChanged(id, IllegalStateException(env.errors?.joinToString { it.message }))
            }
            env.data
        }
    }

    @Serializable internal data class Envelope<T>(val data: T? = null, val errors: List<Err>? = null)
    @Serializable internal data class Err(val message: String = "")
    @Serializable internal data class Batch(val total: Int? = null, val results: List<CreationDto> = emptyList())
    @Serializable internal data class SearchData(val creationsSearchBatch: Batch? = null)
    @Serializable internal data class BrowseData(val creationsBatch: Batch? = null)
    @Serializable internal data class DetailData(val creation: CreationDto? = null)
    @Serializable internal data class CategoriesData(val categories: List<CategoryDto> = emptyList())
    @Serializable internal data class CategoryDto(val id: String, val name: String? = null, val children: List<CategoryDto> = emptyList())
    @Serializable internal data class Money(val cents: Int? = null, val value: Double? = null)
    @Serializable internal data class LicenseDto(val code: String? = null, val name: String? = null, val spdxId: String? = null)
    @Serializable internal data class NickDto(val nick: String? = null)
    @Serializable internal data class NameDto(val name: String? = null)
    @Serializable internal data class BlueprintDto(val fileUrl: String? = null, val imageUrl: String? = null)
    @Serializable internal data class IllustrationDto(val imageUrl: String? = null)

    @Serializable
    internal data class CreationDto(
        val identifier: String? = null,
        val name: String? = null,
        val url: String? = null,
        val shortUrl: String? = null,
        val description: String? = null,
        val publishedAt: String? = null,
        val price: Money? = null,
        val license: LicenseDto? = null,
        val likesCount: Int? = null,
        val downloadsCount: Int? = null,
        val illustrationImageUrl: String? = null,
        val illustrations: List<IllustrationDto> = emptyList(),
        val creator: NickDto? = null,
        val category: NameDto? = null,
        val tags: List<String> = emptyList(),
        val blueprints: List<BlueprintDto> = emptyList(),
    )

    companion object {
        const val ENDPOINT = "https://cults3d.com/graphql"
        const val PAGE_SIZE = 12
        const val CACHE_TTL_MS = 60L * 60 * 1000

        /** Licencje Cults: kody "cults_cu" (komercyjna Cults), SPDX "CC-BY-4.0" itp., własne "LicenseRef-Cults-...". */
        fun cultsLicenseFamily(code: String?, spdx: String?, name: String?): LicenseFamily {
            val text = listOfNotNull(spdx, name, code).joinToString(" ")
            return when {
                text.contains("CC", ignoreCase = false) || text.contains("Creative Commons", true) || text.contains("GPL", true) ->
                    LicenseFamily.fromText(text.replace('-', ' '))
                text.contains("LicenseRef-Cults", true) || text.contains("cults_", true) -> LicenseFamily.STANDARD_DIGITAL
                text.isBlank() -> LicenseFamily.UNKNOWN
                else -> LicenseFamily.OTHER_OPEN
            }
        }

        private const val FIELDS = "identifier name(locale: EN) url(locale: EN) shortUrl publishedAt " +
            "price(currency: EUR) { cents } license { code name(locale: EN) spdxId } likesCount downloadsCount " +
            "illustrationImageUrl creator { nick } category { name(locale: EN) }"

        val SEARCH_QUERY = "query S(\$query: String!, \$limit: Int, \$offset: Int) { creationsSearchBatch(query: \$query, limit: \$limit, offset: \$offset) { total results { $FIELDS } } }"
        val ALLOWED_SORTS = setOf("BY_LIKES", "BY_DOWNLOADS", "BY_PUBLICATION")
        fun browseQuery(sort: String) = "query B(\$limit: Int, \$offset: Int, \$onlyFree: Boolean) { creationsBatch(sort: $sort, limit: \$limit, offset: \$offset, onlyFree: \$onlyFree) { total results { $FIELDS } } }"
        val DETAIL_QUERY = "query D(\$slug: String!) { creation(slug: \$slug) { $FIELDS description(locale: EN) tags(locale: EN) illustrations { imageUrl } blueprints { fileUrl imageUrl } } }"
        val CATEGORIES_QUERY = "query C { categories { id name(locale: EN) children { id name(locale: EN) } } }"
    }
}
