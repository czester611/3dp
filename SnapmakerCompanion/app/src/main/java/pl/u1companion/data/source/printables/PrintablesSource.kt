package pl.u1companion.data.source.printables

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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
import pl.u1companion.data.source.AccessMode
import pl.u1companion.data.source.DownloadTicket
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SearchPage
import pl.u1companion.data.source.SourceCapabilities
import pl.u1companion.data.source.SourceException
import pl.u1companion.data.source.SortResolution
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter Printables (Prusa Research).
 *
 * UWAGA: Printables NIE publikuje oficjalnego API. Endpoint GraphQL https://api.printables.com/graphql/
 * jest używany przez stronę WWW; introspekcja jest wyłączona. Zapytania poniżej zostały
 * zweryfikowane ręcznie 2026-10-17 (searchPrints2, print, getDownloadLink działały anonimowo).
 * Schemat może się zmienić bez ostrzeżenia -> błędy mapujemy na [SourceException.SchemaChanged].
 * Wartości enum SearchChoicesEnum potwierdzone: best_match, latest, popular, rating, makes_count.
 */
@Singleton
class PrintablesSource @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) : ModelSource {

    override val id = SourceId.PRINTABLES

    override val capabilities = SourceCapabilities(
        access = AccessMode.UNOFFICIAL_API,
        supportedSorts = setOf(SortOrder.RELEVANCE, SortOrder.NEWEST, SortOrder.POPULAR, SortOrder.TOP_RATED),
        // searchPrints2(categoryId: ID) - zweryfikowane 2026-10-17; kategoria główna obejmuje podkategorie.
        serverCategoryFilter = true,
        serverLicenseFilter = false,
        printReadyInSearch = true, // pole gcodes{} dostępne w wynikach
        requiresCredential = false,
        inAppDownload = true,
        hasPaidContent = true, // "premium" / płatne modele
    )

    /** Adres GraphQL (podmieniany w testach kontraktowych). */
    internal var endpoint = ENDPOINT

    override fun webModelUrl(modelId: String) = "https://www.printables.com/model/$modelId"

    override fun webSearchUrl(text: String) =
        "https://www.printables.com/search/models?q=" + URLEncoder.encode(text, "UTF-8")

    override suspend fun search(query: SearchQuery, page: PageRequest): SearchPage {
        val offset = page.cursor?.toIntOrNull() ?: 0
        val ordering = resolveSort(query.filters.sort, query.text.isNotBlank()).nativeValue
        val vars = buildJsonObject {
            put("q", query.text)
            put("limit", page.size)
            put("offset", offset)
            put("ordering", ordering)
            page.categoryId?.let { put("categoryId", it) }
        }
        val data = graphql<SearchData>(SEARCH_QUERY, vars)
        val result = data.searchPrints2 ?: throw SourceException.SchemaChanged(id, null)
        val items = result.items.map { it.toSummary() }
        val next = offset + result.items.size
        val total = result.totalCount
        return SearchPage(
            items = items,
            nextCursor = if (result.items.isEmpty() || (total != null && next >= total)) null else next.toString(),
            total = total,
        )
    }

    override suspend fun details(modelId: String): ModelDetail {
        val data = graphql<DetailData>(DETAIL_QUERY, buildJsonObject { put("id", modelId) })
        val p = data.print ?: throw SourceException.SchemaChanged(id, null)
        val files = buildList {
            p.stls.orEmpty().forEach { add(it.toFile("stl")) }
            p.gcodes.orEmpty().forEach { add(it.toFile("gcode")) }
            p.otherFiles.orEmpty().forEach { add(it.toFile("other")) }
        }
        val summary = p.toSummary().copy(
            hasPrintReadyFile = files.any { it.kind == FileKind.GCODE || it.kind == FileKind.THREE_MF },
            hasU1Profile = files.any { it.isLikelyForU1 },
        )
        return ModelDetail(
            summary = summary,
            descriptionHtml = p.description ?: p.summary,
            images = p.images.orEmpty().mapNotNull { it.filePath?.let(::mediaUrl) },
            files = files,
            tags = p.tags.orEmpty().mapNotNull { it.name },
        )
    }

    override suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket {
        val vars = buildJsonObject {
            put("id", file.id)
            put("printId", modelId)
            put("fileType", file.sourceFileType ?: "stl")
            put("source", "model_detail")
        }
        val data = graphql<DownloadData>(DOWNLOAD_MUTATION, vars)
        val link = data.getDownloadLink?.takeIf { it.ok == true }?.output?.link
            ?: throw SourceException.Unsupported(id, "pobranie pliku ${file.name}")
        return DownloadTicket(url = link, fileName = file.name)
    }

    /**
     * Wartości SearchChoicesEnum potwierdzone: best_match, latest, popular, rating, makes_count.
     * Brak sortowania po pobraniach i po polubieniach -> najbliższe odpowiedniki z adnotacją.
     */
    override fun resolveSort(sort: SortOrder, hasText: Boolean): SortResolution = when (sort) {
        SortOrder.RELEVANCE ->
            if (hasText) SortResolution(sort, "best_match", "trafność", true)
            else SortResolution(sort, "popular", "popularne (trafność wymaga frazy)", false)
        SortOrder.NEWEST -> SortResolution(sort, "latest", "najnowsze", true)
        SortOrder.POPULAR -> SortResolution(sort, "popular", "popularne", true)
        SortOrder.TOP_RATED -> SortResolution(sort, "rating", "ocena", true)
        SortOrder.MOST_LIKED -> SortResolution(sort, "popular", "popularne", false)
        SortOrder.MOST_DOWNLOADED -> SortResolution(sort, "makes_count", "liczba wydruków (makes)", false)
    }

    /** Drzewo kategorii (2 poziomy): categories { id name level path { id } }; rodzic = przedostatni element path. */
    override suspend fun categories(): List<Category> {
        val data = graphql<CategoriesData>(CATEGORIES_QUERY, buildJsonObject { })
        return parseCategories(data.categories)
    }

    internal fun parseCategoriesJson(raw: String): List<Category> =
        parseCategories(json.decodeFromString<GqlEnvelope<CategoriesData>>(raw).data?.categories.orEmpty())

    private fun parseCategories(list: List<CategoryNodeDto>): List<Category> = list.map { c ->
        Category(
            id = c.id,
            name = c.name ?: c.id,
            parentId = c.path?.takeIf { it.size >= 2 }?.let { p -> p[p.size - 2].id },
            source = SourceId.PRINTABLES,
        )
    }

    // --- GraphQL ---

    private suspend inline fun <reified T> graphql(query: String, variables: JsonObject): T =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("query", query)
                put("variables", variables)
            }.toString().toRequestBody(JSON_MT)
            val req = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Accept", "application/json")
                .build()
            val raw = try {
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 429) throw SourceException.RateLimited(id, resp.header("Retry-After")?.toLongOrNull())
                    if (!resp.isSuccessful) throw SourceException.Network(id, IOException("HTTP ${resp.code}"))
                    resp.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                throw SourceException.Network(id, e)
            }
            val envelope = try {
                json.decodeFromString<GqlEnvelope<T>>(raw)
            } catch (e: Exception) {
                throw SourceException.SchemaChanged(id, e)
            }
            if (!envelope.errors.isNullOrEmpty() || envelope.data == null) {
                throw SourceException.SchemaChanged(id, IllegalStateException(envelope.errors?.joinToString { it.message }))
            }
            envelope.data
        }

    private fun PrintDto.toSummary() = ModelSummary(
        source = SourceId.PRINTABLES,
        id = id,
        title = name ?: "(bez nazwy)",
        author = user?.publicUsername?.let { n ->
            Author(n, user.handle?.let { "https://www.printables.com/@$it" })
        },
        thumbnailUrl = image?.filePath?.let(::mediaUrl) ?: images?.firstOrNull()?.filePath?.let(::mediaUrl),
        webUrl = "https://www.printables.com/model/$id" + (slug?.let { "-$it" } ?: ""),
        likes = likesCount,
        downloads = downloadCount,
        publishedAtEpochMs = datePublished?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
        license = license?.let { License(it.abbreviation, it.name ?: it.abbreviation ?: "?", LicenseFamily.fromText(it.abbreviation ?: it.name)) },
        isFree = premium != true && (price == null || price == 0.0),
        priceCents = price?.let { (it * 100).toInt() }?.takeIf { it > 0 },
        category = category?.name,
        hasPrintReadyFile = gcodes?.isNotEmpty(),
        hasU1Profile = gcodes?.any { g -> g.name?.let { ModelFile.U1_NAME.containsMatchIn(it) } == true },
    )

    private fun FileDto.toFile(type: String) = ModelFile(
        id = id,
        name = name ?: "plik-$id",
        sizeBytes = fileSize,
        kind = FileKind.fromFileName(name.orEmpty()),
        sourceFileType = type,
    )

    private fun mediaUrl(path: String) = "https://media.printables.com/" + path.trimStart('/')

    // --- DTO ---

    @Serializable private data class GqlEnvelope<T>(val data: T? = null, val errors: List<GqlError>? = null)
    @Serializable private data class GqlError(val message: String = "")
    @Serializable private data class SearchData(val searchPrints2: SearchResult? = null)
    @Serializable private data class SearchResult(val totalCount: Int? = null, val items: List<PrintDto> = emptyList())
    @Serializable private data class DetailData(val print: PrintDto? = null)
    @Serializable private data class CategoriesData(val categories: List<CategoryNodeDto> = emptyList())
    @Serializable private data class CategoryNodeDto(val id: String, val name: String? = null, val level: Int? = null, val path: List<IdDto>? = null)
    @Serializable private data class IdDto(val id: String)
    @Serializable private data class DownloadData(val getDownloadLink: DownloadResult? = null)
    @Serializable private data class DownloadResult(val ok: Boolean? = null, val output: DownloadOutput? = null)
    @Serializable private data class DownloadOutput(val link: String? = null)

    @Serializable
    private data class PrintDto(
        val id: String,
        val name: String? = null,
        val slug: String? = null,
        val summary: String? = null,
        val description: String? = null,
        val likesCount: Int? = null,
        val downloadCount: Int? = null,
        val datePublished: String? = null,
        val premium: Boolean? = null,
        val price: Double? = null,
        val image: ImageDto? = null,
        val images: List<ImageDto>? = null,
        val user: UserDto? = null,
        val license: LicenseDto? = null,
        val category: CategoryDto? = null,
        val tags: List<TagDto>? = null,
        val stls: List<FileDto>? = null,
        val gcodes: List<FileDto>? = null,
        val otherFiles: List<FileDto>? = null,
    )

    @Serializable private data class ImageDto(val filePath: String? = null)
    @Serializable private data class UserDto(val publicUsername: String? = null, val handle: String? = null)
    @Serializable private data class LicenseDto(val name: String? = null, val abbreviation: String? = null)
    @Serializable private data class CategoryDto(val id: String? = null, val name: String? = null)
    @Serializable private data class TagDto(val name: String? = null)
    @Serializable private data class FileDto(val id: String, val name: String? = null, @SerialName("fileSize") val fileSize: Long? = null)

    private companion object {
        const val ENDPOINT = "https://api.printables.com/graphql/"
        val JSON_MT = "application/json".toMediaType()

        val SEARCH_QUERY = """
            query Search(${'$'}q: String!, ${'$'}limit: Int!, ${'$'}offset: Int!, ${'$'}ordering: SearchChoicesEnum, ${'$'}categoryId: ID) {
              searchPrints2(query: ${'$'}q, limit: ${'$'}limit, offset: ${'$'}offset, ordering: ${'$'}ordering, categoryId: ${'$'}categoryId) {
                totalCount
                items {
                  id name slug likesCount downloadCount datePublished premium price
                  image { filePath }
                  user { publicUsername handle }
                  license { name abbreviation }
                  category { id name }
                  gcodes { id name }
                }
              }
            }"""

        val CATEGORIES_QUERY = """
            query Categories { categories { id name level path { id } } }"""

        val DETAIL_QUERY = """
            query Detail(${'$'}id: ID!) {
              print(id: ${'$'}id) {
                id name slug summary description likesCount downloadCount datePublished premium price
                user { publicUsername handle }
                license { name abbreviation }
                category { id name }
                tags { name }
                images { filePath }
                stls { id name fileSize }
                gcodes { id name fileSize }
                otherFiles { id name fileSize }
              }
            }"""

        val DOWNLOAD_MUTATION = """
            mutation Download(${'$'}id: ID!, ${'$'}printId: ID!, ${'$'}fileType: DownloadFileTypeEnum!, ${'$'}source: DownloadSourceEnum!) {
              getDownloadLink(id: ${'$'}id, printId: ${'$'}printId, fileType: ${'$'}fileType, source: ${'$'}source) {
                ok
                output { link }
              }
            }"""
    }
}
