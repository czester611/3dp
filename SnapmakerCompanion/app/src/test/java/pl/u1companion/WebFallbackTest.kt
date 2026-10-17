package pl.u1companion

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.settings.ApiCredentials
import pl.u1companion.data.source.WebFallbackPolicy
import pl.u1companion.data.source.WebFallbackPolicy.DownloadRoute
import pl.u1companion.data.source.cults3d.Cults3dSource
import pl.u1companion.data.source.demo.DemoSource
import pl.u1companion.data.source.myminifactory.MyMiniFactorySource
import pl.u1companion.data.source.printables.PrintablesSource
import pl.u1companion.data.source.thingiverse.ThingiverseSource
import pl.u1companion.data.source.web.WebAssistedSource

/** Serwis bez klucza lub bez API -> zawsze dostępny przez stronę WWW; skonfigurowane działają jak dotąd. */
class WebFallbackTest {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val noKeys = ApiCredentials { null }

    private val printables = PrintablesSource(http, json)
    private val thingiverse = ThingiverseSource(http, json, noKeys)
    private val mmf = MyMiniFactorySource(http, json, noKeys)
    private val cults = Cults3dSource(http, json, noKeys)
    private val makerworld = WebAssistedSource.defaults().first { it.id == SourceId.MAKERWORLD }

    @Test fun unconfiguredKeyedSourcesOfferWeb() {
        listOf(thingiverse, mmf, cults).forEach {
            assertTrue("${it.id} bez klucza -> przeglądarka", WebFallbackPolicy.offerWeb(it, configured = false))
            assertEquals(DownloadRoute.WEB, WebFallbackPolicy.downloadRoute(it, configured = false))
            assertFalse(WebFallbackPolicy.usableViaApi(it, configured = false))
        }
    }

    @Test fun configuredSourcesKeepCurrentBehaviour() {
        assertFalse(WebFallbackPolicy.offerWeb(thingiverse, configured = true))
        assertEquals(DownloadRoute.IN_APP, WebFallbackPolicy.downloadRoute(thingiverse, configured = true))
        // MMF/Cults3D: wyszukiwanie przez API, ale pobieranie zawsze przez stronę (API nie pozwala)
        assertFalse(WebFallbackPolicy.offerWeb(mmf, configured = true))
        assertEquals(DownloadRoute.WEB, WebFallbackPolicy.downloadRoute(mmf, configured = true))
        assertEquals(DownloadRoute.WEB, WebFallbackPolicy.downloadRoute(cults, configured = true))
        // Printables nie wymaga klucza
        assertFalse(WebFallbackPolicy.offerWeb(printables, configured = false))
        assertEquals(DownloadRoute.IN_APP, WebFallbackPolicy.downloadRoute(printables, configured = false))
    }

    @Test fun noApiSourcesAlwaysWebAndDemoNever() {
        assertTrue(WebFallbackPolicy.offerWeb(makerworld, configured = false))
        assertTrue(WebFallbackPolicy.offerWeb(makerworld, configured = true))
        assertEquals(DownloadRoute.WEB, WebFallbackPolicy.downloadRoute(makerworld, true))
        assertFalse(WebFallbackPolicy.offerWeb(DemoSource(), configured = false))
    }

    @Test fun webModelUrls() {
        assertEquals("https://www.thingiverse.com/thing:763622", thingiverse.webModelUrl("763622"))
        assertEquals("https://www.myminifactory.com/object/101", mmf.webModelUrl("101"))
        assertEquals("https://www.printables.com/model/3161", printables.webModelUrl("3161"))
        assertTrue(cults.webModelUrl("benchy-remix").contains("q=benchy+remix"))
        assertTrue(makerworld.webSearchUrl("benchy boat").contains("keyword=benchy+boat"))
    }
}
