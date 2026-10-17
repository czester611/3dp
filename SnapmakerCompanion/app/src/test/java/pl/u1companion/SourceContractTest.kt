package pl.u1companion

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.core.model.FileKind
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.settings.ApiCredential
import pl.u1companion.data.settings.ApiCredentials
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SourceException
import pl.u1companion.data.source.cults3d.Cults3dSource
import pl.u1companion.data.source.myminifactory.MyMiniFactorySource
import pl.u1companion.data.source.printables.PrintablesSource
import pl.u1companion.data.source.thingiverse.ThingiverseSource
import java.util.Base64

/**
 * Testy kontraktowe adapterów na MockWebServer.
 *
 * UWAGA: odpowiedzi MyMiniFactory, Cults3D i Thingiverse są ZMYŚLONE, ale zgodne ze specyfikacją
 * (MMF: myminifactory-api.yaml; Cults3D: notatki społeczności cults3d-api-docs; Thingiverse: klienci zewn.).
 * Adaptery NIE były testowane z prawdziwymi kluczami API. Printables - odpowiedź NAGRANA z prawdziwego API (2026-10-17).
 */
class SourceContractTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

    private fun creds(c: ApiCredential?) = ApiCredentials { c }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base(path: String) = server.url(path).toString().trimEnd('/')
    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    // ---------------- MyMiniFactory ----------------

    private val mmfSearch = """
        {"total_count": 2, "items": [
          {"id": 101, "url": "https://www.myminifactory.com/object/3d-print-cable-organizer-101", "name": "Cable organizer",
           "likes": 42, "views": 900, "published_at": "2026-05-01T10:00:00+00:00",
           "designer": {"username": "maker1", "name": "Maker One", "profile_url": "https://www.myminifactory.com/users/maker1"},
           "images": [{"is_primary": true, "thumbnail": {"url": "https://cdn.mmf/thumb101.jpg"}, "standard": {"url": "https://cdn.mmf/std101.jpg"}}],
           "categories": [{"id": 5, "name": "Home"}],
           "licenses": [{"type": "commercial-use", "value": false}, {"type": "remix", "value": true}, {"type": "store", "value": false}],
           "license": "MyMiniFactory licence: attribution, non-commercial, remix"},
          {"id": 102, "name": "Premium dragon", "images": [],
           "licenses": [{"type": "store", "value": true}]}
        ]}"""

    @Test fun mmfSearchRequestAndMapping() = runBlocking {
        server.enqueue(ok(mmfSearch))
        val src = MyMiniFactorySource(http, json, creds(ApiCredential.Token("KEY123"))).apply { base = base("/api/v2") }
        val page = src.search(
            SearchQuery("organizer", SearchFilters(sort = SortOrder.NEWEST, onlyCommercialUse = true)),
            PageRequest(null, 2, categoryId = "5"),
        )
        val req = server.takeRequest()
        val url = req.requestUrl!!
        assertEquals("/api/v2/search", url.encodedPath)
        assertEquals("organizer", url.queryParameter("q"))
        assertEquals("KEY123", url.queryParameter("key"))
        assertEquals("date", url.queryParameter("sort"))
        assertEquals("5", url.queryParameter("cat"))
        assertEquals("1", url.queryParameter("commercial_use"))

        assertEquals(2, page.items.size)
        val a = page.items[0]
        assertEquals("101", a.id)
        assertEquals("Maker One", a.author?.name)
        assertEquals("https://cdn.mmf/thumb101.jpg", a.thumbnailUrl)
        assertEquals(LicenseFamily.CC_BY_NC, a.license?.family) // flagi: remix, bez komercji
        assertTrue(a.isFree)
        assertEquals("Home", a.category)
        val b = page.items[1]
        assertFalse("licencja store = płatny", b.isFree)
        assertEquals(LicenseFamily.STANDARD_DIGITAL, b.license?.family)
        assertEquals(null, page.nextCursor) // total_count 2 osiągnięty
    }

    @Test fun mmfDetailsDimensionsAndNoInAppDownload() = runBlocking {
        server.enqueue(
            ok(
                """{"id": 101, "name": "Cable organizer", "dimensions": "120 x 80.5 x 40 mm",
                 "description_html": "<p>Opis</p>", "tags": ["cable"],
                 "files": [{"id": 9, "filename": "organizer_Snapmaker_U1.3mf", "size": "123456"}],
                 "images": [{"is_primary": false, "standard": {"url": "https://cdn/2.jpg"}}, {"is_primary": true, "standard": {"url": "https://cdn/1.jpg"}}]}""",
            ),
        )
        val src = MyMiniFactorySource(http, json, creds(ApiCredential.Token("K"))).apply { base = base("/api/v2") }
        val d = src.details("101")
        assertEquals("/api/v2/objects/101", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(120f, d.dimensions!!.xMm, 0.01f)
        assertEquals(80.5f, d.dimensions!!.yMm, 0.01f)
        assertEquals("https://cdn/1.jpg", d.images.first())
        assertEquals(true, d.summary.hasU1Profile)
        assertEquals(123456L, d.files.single().sizeBytes)
        assertFalse(src.capabilities.inAppDownload)
        try { src.resolveDownload("101", d.files.single()); fail() } catch (e: SourceException.Unsupported) { }
    }

    @Test fun mmfCategoriesFlatten() = runBlocking {
        server.enqueue(ok("""{"total_count":1,"items":[{"id":1,"slug":"home","name":"Home","children":{"total_count":2,"items":[{"id":11,"slug":"kitchen","name":"Kitchen"},{"id":12,"name":"Garden"}]}}]}"""))
        val cats = MyMiniFactorySource(http, json, creds(ApiCredential.Token("K"))).apply { base = base("/api/v2") }.categories()
        assertEquals(listOf("1", "11", "12"), cats.map { it.id })
        assertEquals("1", cats.first { it.name == "Kitchen" }.parentId)
    }

    @Test fun mmfErrors() = runBlocking {
        val noKey = MyMiniFactorySource(http, json, creds(null)).apply { base = base("/api/v2") }
        assertFalse(noKey.isAvailable())
        try { noKey.search(SearchQuery("x"), PageRequest(null, 10)); fail() } catch (e: SourceException.NotConfigured) { }
        server.enqueue(MockResponse().setResponseCode(401))
        val bad = MyMiniFactorySource(http, json, creds(ApiCredential.Token("BAD"))).apply { base = base("/api/v2") }
        try { bad.search(SearchQuery("x"), PageRequest(null, 10)); fail() } catch (e: SourceException.Unauthorized) { }
    }

    @Test fun mmfDimensionsParser() {
        assertEquals(100f, MyMiniFactorySource.parseDimensions("10 x 5 x 2 cm")!!.xMm, 0.01f)
        assertEquals(null, MyMiniFactorySource.parseDimensions("duży"))
    }

    // ---------------- Cults3D ----------------

    private val cultsBatch = """
        {"data": {"creationsSearchBatch": {"total": 5, "results": [
          {"identifier": "abc", "name": "Benchy remix", "url": "https://cults3d.com/en/3d-model/various/benchy-remix",
           "shortUrl": "https://cults3d.com/:123", "publishedAt": "2026-01-02T03:04:05+01:00",
           "price": {"cents": 0}, "license": {"code": "cc_by", "name": "CC BY", "spdxId": "CC-BY-4.0"},
           "likesCount": 10, "downloadsCount": 200, "illustrationImageUrl": "https://images.cults3d.com/a.jpg",
           "creator": {"nick": "bob"}, "category": {"name": "Various"}},
          {"identifier": "def", "name": "Paid dragon", "url": "https://cults3d.com/en/3d-model/art/paid-dragon",
           "price": {"cents": 499}, "license": {"code": "cults_cu", "name": "Cults - commercial use", "spdxId": "LicenseRef-Cults-CU"}}
        ]}}}"""

    @Test fun cultsSearchRequestAndMapping() = runBlocking {
        server.enqueue(ok(cultsBatch))
        val src = Cults3dSource(http, json, creds(ApiCredential.UserKey("bob", "secret"))).apply { endpoint = base("/graphql") }
        val page = src.search(SearchQuery("benchy"), PageRequest(null, 2))
        val req = server.takeRequest()
        assertEquals("Basic " + Base64.getEncoder().encodeToString("bob:secret".toByteArray()), req.getHeader("Authorization"))
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertTrue(body["query"]!!.jsonPrimitive.content.contains("creationsSearchBatch"))
        assertEquals("benchy", body["variables"]!!.jsonObject["query"]!!.jsonPrimitive.content)

        assertEquals("benchy-remix", page.items[0].id)
        assertEquals(LicenseFamily.CC_BY, page.items[0].license?.family)
        assertTrue(page.items[0].isFree)
        assertFalse(page.items[1].isFree)
        assertEquals(499, page.items[1].priceCents)
        assertEquals(LicenseFamily.STANDARD_DIGITAL, page.items[1].license?.family)
        assertEquals("2", page.nextCursor) // total 5 > offset 2
    }

    @Test fun cultsBrowseUsesSortLiteralAndOnlyFree() = runBlocking {
        server.enqueue(ok("""{"data": {"creationsBatch": {"total": 0, "results": []}}}"""))
        val src = Cults3dSource(http, json, creds(ApiCredential.UserKey("u", "k"))).apply { endpoint = base("/graphql") }
        val page = src.search(SearchQuery("", SearchFilters(sort = SortOrder.MOST_DOWNLOADED, onlyFree = true)), PageRequest(null, 20))
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["query"]!!.jsonPrimitive.content.contains("creationsBatch(sort: BY_DOWNLOADS"))
        assertEquals("true", body["variables"]!!.jsonObject["onlyFree"]!!.jsonPrimitive.content)
        assertEquals(null, page.nextCursor)
        assertFalse(src.resolveSort(SortOrder.NEWEST, hasText = false).exact)
    }

    @Test fun cultsGraphqlErrorIsSchemaChanged() = runBlocking {
        server.enqueue(ok("""{"errors": [{"message": "Field 'creationsSearchBatch' doesn't exist"}]}"""))
        val src = Cults3dSource(http, json, creds(ApiCredential.UserKey("u", "k"))).apply { endpoint = base("/graphql") }
        try { src.search(SearchQuery("x"), PageRequest(null, 5)); fail() } catch (e: SourceException.SchemaChanged) { }
    }

    @Test fun cultsDetailsBySlug() = runBlocking {
        server.enqueue(
            ok(
                """{"data":{"creation":{"name":"Benchy remix","url":"https://cults3d.com/en/3d-model/various/benchy-remix",
                "description":"<p>x</p>","tags":["boat"],"illustrations":[{"imageUrl":"https://i/1.jpg"}],
                "blueprints":[{"fileUrl":"https://files.cults3d.com/benchy_U1.gcode?x=1"},{"fileUrl":"https://files.cults3d.com/benchy.stl"}]}}}""",
            ),
        )
        val src = Cults3dSource(http, json, creds(ApiCredential.UserKey("u", "k"))).apply { endpoint = base("/graphql") }
        val d = src.details("benchy-remix")
        val vars = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["variables"]!!.jsonObject
        assertEquals("benchy-remix", vars["slug"]!!.jsonPrimitive.content)
        assertEquals(listOf("benchy_U1.gcode", "benchy.stl"), d.files.map { it.name })
        assertEquals(FileKind.GCODE, d.files[0].kind)
        assertEquals(true, d.summary.hasU1Profile)
    }

    // ---------------- Thingiverse ----------------

    @Test fun thingiverseSearchRequest() = runBlocking {
        server.enqueue(
            ok(
                """{"total": 1, "hits": [{"id": 763622, "name": "Benchy", "thumbnail": "https://cdn.thingiverse.com/t.jpg",
                "public_url": "https://www.thingiverse.com/thing:763622", "creator": {"name": "CreativeTools"},
                "like_count": 100, "download_count": 1000, "added": "2015-04-09T11:34:56+00:00"}]}""",
            ),
        )
        val src = ThingiverseSource(http, json, creds(ApiCredential.Token("TOK"))).apply { base = base("") }
        val page = src.search(SearchQuery("3d benchy", SearchFilters(sort = SortOrder.NEWEST)), PageRequest(null, 20))
        val req = server.takeRequest()
        assertEquals("Bearer TOK", req.getHeader("Authorization"))
        assertEquals("/search/3d%20benchy", req.requestUrl!!.encodedPath)
        assertEquals("things", req.requestUrl!!.queryParameter("type"))
        assertEquals("newest", req.requestUrl!!.queryParameter("sort"))
        assertEquals("763622", page.items.single().id)
        assertEquals("CreativeTools", page.items.single().author?.name)
    }

    // ---------------- Printables (nagrana odpowiedź) ----------------

    @Test fun printablesRecordedSearchWithCategory() = runBlocking {
        val recorded = javaClass.getResource("/fixtures/printables_search_benchy_cat1.json")!!.readText()
        server.enqueue(ok(recorded))
        val src = PrintablesSource(http, json).apply { endpoint = base("/graphql/") + "/" }
        val page = src.search(SearchQuery("benchy", SearchFilters(sort = SortOrder.POPULAR)), PageRequest(null, 3, categoryId = "1"))
        val vars = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["variables"]!!.jsonObject
        assertEquals("1", vars["categoryId"]!!.jsonPrimitive.content)
        assertEquals("popular", vars["ordering"]!!.jsonPrimitive.content)
        assertEquals(3, page.items.size)
        assertEquals("3161", page.items.first().id)
        assertNotNull(page.items.first().thumbnailUrl)
        assertEquals("3", page.nextCursor)
        assertEquals(LicenseFamily.CC0, page.items.first().license?.family)
        assertEquals(SourceId.PRINTABLES, page.items.first().source)
    }
}
