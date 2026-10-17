package pl.u1companion.data.source

import pl.u1companion.core.model.Category
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceId

/**
 * Sposób dostępu do serwisu. Wpływa na UI (np. ostrzeżenia, przycisk "otwórz w przeglądarce").
 */
enum class AccessMode {
    /** Udokumentowane API z kluczem (np. Thingiverse App Token, MyMiniFactory key, Cults3D key). */
    OFFICIAL_API,
    /** Nieudokumentowane, publicznie dostępne API używane przez stronę WWW (np. Printables GraphQL). Może się zmienić bez ostrzeżenia. */
    UNOFFICIAL_API,
    /** Brak API - wbudowana przeglądarka (WebView) + przechwycenie pobierania pliku przez użytkownika. */
    ASSISTED_WEBVIEW,
    /** Tylko otwarcie wyszukiwania w zewnętrznej przeglądarce. */
    LINK_OUT,
    /** Dane testowe. */
    DEMO,
}

data class SourceCapabilities(
    val access: AccessMode,
    val supportedSorts: Set<SortOrder>,
    /** Czy filtr kategorii jest realizowany po stronie serwera. */
    val serverCategoryFilter: Boolean = false,
    /** Czy filtr licencji jest po stronie serwera (inaczej filtrujemy po stronie klienta - mniej wyników na stronę). */
    val serverLicenseFilter: Boolean = false,
    /** Czy wyniki wyszukiwania niosą informację o gotowym G-code (bez dociągania szczegółów). */
    val printReadyInSearch: Boolean = false,
    /** Wymaga klucza/tokenu użytkownika (konfiguracja w Ustawieniach). */
    val requiresCredential: Boolean = false,
    /** Czy pobieranie plików jest możliwe bezpośrednio z aplikacji. */
    val inAppDownload: Boolean = false,
    /** Serwis ma płatne modele (filtr "tylko darmowe" ma znaczenie). */
    val hasPaidContent: Boolean = false,
)

data class PageRequest(val cursor: String?, val size: Int)

data class SearchPage(
    val items: List<ModelSummary>,
    /** null = koniec wyników. */
    val nextCursor: String?,
    val total: Int? = null,
)

data class DownloadTicket(
    val url: String,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
)

sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured(val source: SourceId) : SourceException("Serwis ${source.displayName} wymaga konfiguracji (klucz API)")
    class Unauthorized(val source: SourceId) : SourceException("Nieprawidłowy lub wygasły klucz dla ${source.displayName}")
    class RateLimited(val source: SourceId, val retryAfterSec: Long?) : SourceException("Limit zapytań ${source.displayName}")
    class SchemaChanged(val source: SourceId, cause: Throwable?) :
        SourceException("Odpowiedź ${source.displayName} ma nieoczekiwany format (zmiana API?)", cause)
    class Unsupported(val source: SourceId, what: String) : SourceException("${source.displayName}: $what nieobsługiwane")
    class Network(val source: SourceId, cause: Throwable) : SourceException("Błąd sieci (${source.displayName})", cause)
}

/**
 * Abstrakcja serwisu z modelami 3D. Jeden adapter na serwis.
 *
 * Kontrakt:
 *  - [search] zwraca stronę wyników; kursor jest nieprzezroczysty (offset, numer strony, token).
 *  - Filtry, których serwis nie obsługuje po stronie serwera, adapter MOŻE zignorować -
 *    [ClientSideFilter] dofiltruje wyniki po stronie aplikacji.
 *  - Adapter nie trzyma stanu UI; cache realizuje warstwa repozytorium.
 */
interface ModelSource {
    val id: SourceId
    val capabilities: SourceCapabilities

    /** true gdy adapter może działać (np. jest token). */
    suspend fun isAvailable(): Boolean = true

    suspend fun search(query: SearchQuery, page: PageRequest): SearchPage

    suspend fun details(modelId: String): ModelDetail

    suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket

    suspend fun categories(): List<Category> = emptyList()

    /** URL do wyszukiwania w przeglądarce (fallback dla ASSISTED_WEBVIEW/LINK_OUT). */
    fun webSearchUrl(text: String): String
}
