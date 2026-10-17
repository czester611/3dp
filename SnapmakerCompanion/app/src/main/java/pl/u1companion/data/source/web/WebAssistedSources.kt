package pl.u1companion.data.source.web

import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.source.AccessMode
import pl.u1companion.data.source.DownloadTicket
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SearchPage
import pl.u1companion.data.source.SourceCapabilities
import pl.u1companion.data.source.SourceException
import java.net.URLEncoder

/**
 * Serwisy bez (dostępnego dla nas) API. Obsługa przez wbudowany WebView:
 * użytkownik sam przegląda stronę serwisu (z jego logowaniem, captcha, Cloudflare),
 * a aplikacja przechwytuje pobranie pliku (DownloadListener) i dodaje go do biblioteki.
 * To podejście nie omija zabezpieczeń i jest zgodne z "normalnym" korzystaniem ze strony.
 *
 * Wyniki tych serwisów NIE trafiają do zagregowanej listy wyszukiwania.
 */
class WebAssistedSource(
    override val id: SourceId,
    private val searchUrlTemplate: String,
    access: AccessMode = AccessMode.ASSISTED_WEBVIEW,
) : ModelSource {

    override val capabilities = SourceCapabilities(
        access = access,
        supportedSorts = emptySet(),
        inAppDownload = access == AccessMode.ASSISTED_WEBVIEW,
        hasPaidContent = true,
    )

    override fun webSearchUrl(text: String): String =
        searchUrlTemplate.replace("{q}", URLEncoder.encode(text, "UTF-8"))

    override suspend fun search(query: SearchQuery, page: PageRequest): SearchPage =
        throw SourceException.Unsupported(id, "wyszukiwanie przez API")

    override suspend fun details(modelId: String): ModelDetail =
        throw SourceException.Unsupported(id, "szczegóły przez API")

    override suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket =
        throw SourceException.Unsupported(id, "pobieranie przez API")

    companion object {
        /**
         * Szablony URL wyszukiwania. Zweryfikowany tylko format MakerWorld (/en/search/models?keyword=),
         * pozostałe na podstawie publicznych URL-i stron - do sprawdzenia w etapie 2.
         */
        fun defaults(): List<WebAssistedSource> = listOf(
            WebAssistedSource(SourceId.MAKERWORLD, "https://makerworld.com/en/search/models?keyword={q}"),
            WebAssistedSource(SourceId.THANGS, "https://thangs.com/search/{q}?scope=all"),
            // MyMiniFactory i Cults3D mają oficjalne API z kluczem - adaptery w etapie 3; do tego czasu WebView.
            WebAssistedSource(SourceId.MYMINIFACTORY, "https://www.myminifactory.com/search/?query={q}"),
            WebAssistedSource(SourceId.CULTS3D, "https://cults3d.com/en/search?q={q}"),
            WebAssistedSource(SourceId.CREALITY_CLOUD, "https://www.crealitycloud.com/search?keyword={q}"),
            WebAssistedSource(SourceId.GRABCAD, "https://grabcad.com/library?query={q}"),
            WebAssistedSource(SourceId.CGTRADER, "https://www.cgtrader.com/3d-print-models?keywords={q}"),
            WebAssistedSource(SourceId.YEGGI, "https://www.yeggi.com/q/{q}/", AccessMode.LINK_OUT),
        )
    }
}
