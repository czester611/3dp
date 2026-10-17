package pl.u1companion

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.data.settings.ApiCredential
import pl.u1companion.data.settings.ApiCredentials
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SourceException
import pl.u1companion.data.source.TtlCache
import pl.u1companion.data.source.cults3d.Cults3dSource

class CacheTest {

    @Test fun ttlCacheHitAndExpiry() {
        var now = 1_000L
        val c = TtlCache<String, String>(ttlMs = 3_600_000, clock = { now })
        c.put("q", "wynik")
        now += 3_599_999
        assertEquals("wynik", c.get("q"))          // trafienie tuż przed upływem 1 h
        now += 1
        assertNull(c.get("q"))                      // wygasło po 1 h
        assertEquals(0, c.size)
    }

    @Test fun ttlCacheEvictsOldest() {
        val c = TtlCache<Int, Int>(ttlMs = 10_000, maxEntries = 2, clock = { 0L })
        c.put(1, 1); c.put(2, 2); c.put(3, 3)
        assertNull(c.get(1)); assertEquals(3, c.get(3))
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val body = """{"data":{"creationsSearchBatch":{"total":30,"results":[{"name":"A","url":"https://cults3d.com/en/3d-model/x/a"}]}}}"""

    /** Cults3D: to samo zapytanie+strona w ciągu 1 h nie idzie do sieci; inna strona / po 1 h - idzie. */
    @Test fun cultsUsesCacheAndSmallPages() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            repeat(3) { server.enqueue(MockResponse().setBody(body)) }
            var now = 0L
            val src = Cults3dSource(OkHttpClient(), json, ApiCredentials { ApiCredential.UserKey("u", "k") }).apply {
                endpoint = server.url("/graphql").toString()
                searchCache = TtlCache(Cults3dSource.CACHE_TTL_MS, clock = { now })
            }
            val q = SearchQuery("benchy", SearchFilters())
            src.search(q, PageRequest(null, 20))
            val firstBody = server.takeRequest().body.readUtf8()
            assertTrue("rozmiar strony ograniczony do 12", firstBody.contains("\"limit\":12"))
            src.search(q, PageRequest(null, 20))                 // trafienie w cache
            assertEquals(1, server.requestCount)
            src.search(q, PageRequest("12", 20))                 // inna strona -> sieć
            assertEquals(2, server.requestCount)
            now += Cults3dSource.CACHE_TTL_MS                    // po 1 h -> sieć
            src.search(q, PageRequest(null, 20))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cults429IsRateLimitedWithReadableMessage() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "300"))
            val src = Cults3dSource(OkHttpClient(), json, ApiCredentials { ApiCredential.UserKey("u", "k") }).apply {
                endpoint = server.url("/graphql").toString()
            }
            try {
                src.search(SearchQuery("x"), PageRequest(null, 12)); fail()
            } catch (e: SourceException.RateLimited) {
                assertEquals(300L, e.retryAfterSec)
                assertTrue(e.message!!.contains("5 min"))
                assertTrue(e.message!!.contains("Cults3D"))
            }
            assertEquals(0, src.searchCache.size)               // błąd nie trafia do cache
        } finally { server.shutdown() }
    }
}
