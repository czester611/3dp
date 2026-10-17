package pl.u1companion.data.source

/**
 * Kiedy kierować użytkownika do webowej wersji serwisu (wbudowana przeglądarka, pobrany plik -> Biblioteka).
 *
 * Zasada: serwis bez API, albo z API wymagającym klucza, którego nie ustawiono, ZAWSZE da się przeglądać
 * przez stronę WWW. Skonfigurowane serwisy z API działają jak dotąd (wyniki w liście, pobieranie w aplikacji,
 * o ile API na to pozwala).
 */
object WebFallbackPolicy {

    enum class DownloadRoute { IN_APP, WEB }

    private val API_MODES = setOf(AccessMode.OFFICIAL_API, AccessMode.UNOFFICIAL_API)

    fun hasApi(src: ModelSource) = src.capabilities.access in API_MODES

    /** Czy źródło da się teraz używać przez API (jest API i - jeśli wymagany - klucz). */
    fun usableViaApi(src: ModelSource, configured: Boolean) =
        hasApi(src) && (!src.capabilities.requiresCredential || configured)

    /** Czy chip/stopka "↗" ma oferować ten serwis w przeglądarce. */
    fun offerWeb(src: ModelSource, configured: Boolean): Boolean =
        src.capabilities.access != AccessMode.DEMO && !usableViaApi(src, configured)

    /** Pobieranie pliku: w aplikacji tylko, gdy API jest używalne i pozwala pobierać; inaczej strona modelu w WebView. */
    fun downloadRoute(src: ModelSource, configured: Boolean): DownloadRoute =
        if (usableViaApi(src, configured) && src.capabilities.inAppDownload) DownloadRoute.IN_APP else DownloadRoute.WEB
}
