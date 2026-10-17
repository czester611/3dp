package pl.u1companion.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.u1companion.core.model.Dimensions
import pl.u1companion.core.model.FileKind
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.local.HistoryType
import pl.u1companion.data.repo.LibraryRepository
import pl.u1companion.data.repo.ModelRepository
import pl.u1companion.download.DownloadRepository
import pl.u1companion.data.source.SourceRegistry
import pl.u1companion.data.source.WebFallbackPolicy
import pl.u1companion.data.repo.GeometryRepository
import pl.u1companion.geometry.FitStatus
import pl.u1companion.geometry.GeometryAnalyzer
import pl.u1companion.geometry.ModelFacts
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

sealed interface DetailState {
    data object Loading : DetailState
    data class Ready(val detail: ModelDetail) : DetailState
    data class Error(val message: String) : DetailState
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: ModelRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val geometry: GeometryRepository,
    private val registry: SourceRegistry,
) : ViewModel() {
    val state = MutableStateFlow<DetailState>(DetailState.Loading)
    private val modelKey = MutableStateFlow<String?>(null)

    /** Wymiary / profil U1 z analizy pobranych plików tego modelu (null = jeszcze nie analizowano). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val facts: StateFlow<ModelFacts?> = modelKey
        .flatMapLatest { k -> if (k == null) flowOf(null) else geometry.factsFor(k) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val favoriteKeys: StateFlow<Set<String>> = library.favoriteKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    private val _openPrint = Channel<Long>(Channel.BUFFERED)
    val openPrint = _openPrint.receiveAsFlow()

    /** Pobieranie w aplikacji tylko gdy API jest skonfigurowane i pozwala pobierać; inaczej strona modelu w WebView. */
    val inAppDownload = MutableStateFlow(false)

    /** Strona modelu w serwisie (do przycisku "Otwórz w przeglądarce", także gdy szczegóły z API się nie wczytały). */
    fun webModelUrl(source: SourceId, id: String) = registry[source].webModelUrl(id)

    fun load(source: SourceId, id: String) = viewModelScope.launch {
        modelKey.value = "${source.name}:$id"
        val src = registry[source]
        val configured = runCatching { src.isAvailable() }.getOrDefault(false)
        inAppDownload.value = WebFallbackPolicy.downloadRoute(src, configured) == WebFallbackPolicy.DownloadRoute.IN_APP
        state.value = DetailState.Loading
        state.value = runCatching { repo.details(source, id) }
            .onSuccess { library.recordHistory(HistoryType.VIEWED, it.summary) }
            .fold({ DetailState.Ready(it) }, { DetailState.Error(it.message ?: "Błąd") })
    }

    /** Pobiera najmniejszy plik STL/3MF, żeby policzyć wymiary (analiza po pobraniu w DownloadWorker). */
    fun checkDimensions(d: ModelDetail) = viewModelScope.launch {
        if (!inAppDownload.value) return@launch
        val f = d.files.filter { GeometryAnalyzer.supports(it.kind) }.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE } ?: return@launch
        downloads.enqueue(d.summary, f)
    }


    fun toggleFavorite(d: ModelDetail) = viewModelScope.launch { library.toggleFavorite(d.summary, d.summary.key in favoriteKeys.value) }

    /** Pobiera plik; jeśli [thenPrint] - przechodzi do konfiguracji druku (ekran czeka na zakończenie pobierania). */
    fun download(d: ModelDetail, f: ModelFile, thenPrint: Boolean) = viewModelScope.launch {
        val id = downloads.enqueue(d.summary, f)
        library.recordHistory(HistoryType.DOWNLOADED, d.summary, detail = f.name)
        if (thenPrint) _openPrint.send(id)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    source: SourceId,
    modelId: String,
    onBack: () -> Unit,
    onPrint: (libraryFileId: Long) -> Unit,
    /** Otwarcie strony modelu w wbudowanej przeglądarce (serwisy bez pobierania przez API). */
    onOpenWeb: (source: SourceId, url: String, modelKey: String) -> Unit = { _, _, _ -> },
    vm: DetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(source, modelId) { vm.load(source, modelId) }
    LaunchedEffect(Unit) { vm.openPrint.collect(onPrint) }
    val state by vm.state.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val inApp by vm.inAppDownload.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Scaffold(topBar = {
        TopAppBar(
            title = { Text((state as? DetailState.Ready)?.detail?.summary?.title ?: "Szczegóły", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            actions = {
                (state as? DetailState.Ready)?.detail?.let { d ->
                    IconButton(onClick = { vm.toggleFavorite(d) }) {
                        Icon(if (d.summary.key in favs) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Ulubione")
                    }
                    IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.summary.webUrl))) }) {
                        Icon(Icons.Default.OpenInBrowser, "Otwórz w przeglądarce")
                    }
                }
            },
        )
    }) { pad ->
        when (val s = state) {
            DetailState.Loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is DetailState.Error -> Column(Modifier.padding(pad).padding(24.dp)) {
                Text("Nie udało się wczytać modelu: ${s.message}")
                Button(onClick = { vm.load(source, modelId) }) { Text("Ponów") }
                // Np. brak klucza API: model nadal można obejrzeć i pobrać na stronie serwisu.
                OutlinedButton(onClick = { onOpenWeb(source, vm.webModelUrl(source, modelId), "${source.name}:$modelId") }) {
                    Text("Otwórz stronę modelu w przeglądarce")
                }
            }
            is DetailState.Ready -> DetailContent(
                s.detail, facts, Modifier.padding(pad), inApp,
                onDownload = { f, print ->
                    if (inApp) vm.download(s.detail, f, print)
                    else onOpenWeb(s.detail.summary.source, s.detail.summary.webUrl, s.detail.summary.key)
                },
                onCheckDimensions = { vm.checkDimensions(s.detail) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailContent(
    d: ModelDetail,
    facts: ModelFacts?,
    modifier: Modifier,
    inAppDownload: Boolean,
    onDownload: (ModelFile, Boolean) -> Unit,
    onCheckDimensions: () -> Unit,
) {
    val m = d.summary
    Column(modifier.verticalScroll(rememberScrollState())) {
        // Galeria
        val images = d.images.ifEmpty { listOfNotNull(m.thumbnailUrl) }
        val pager = rememberPagerState { images.size }
        HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(4f / 3f)) { page ->
            AsyncImage(images[page], null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall)

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.title, style = MaterialTheme.typography.headlineSmall)
            Text("${m.source.displayName} · ${m.author?.name ?: "autor nieznany"}" + (m.category?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(m.likes?.let { "♥ $it" }, m.downloads?.let { "⬇ $it" }).joinToString("   "), style = MaterialTheme.typography.bodySmall)

            // Licencja - zawsze widoczna przed pobraniem/drukiem
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Licencja: ${m.license?.name ?: "nieznana"}", style = MaterialTheme.typography.titleSmall)
                    m.license?.family?.let { f ->
                        Text(
                            (if (f.commercial) "✓ użytek komercyjny" else "✗ tylko niekomercyjnie") + "   " +
                                (if (f.derivatives) "✓ modyfikacje" else "✗ bez modyfikacji"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Pamiętaj o uznaniu autorstwa przy udostępnianiu wydruków/remiksów.", style = MaterialTheme.typography.bodySmall)
                }
            }

            DimensionsBlock(d, facts, onCheckDimensions)

            Text("Pliki", style = MaterialTheme.typography.titleMedium)
            if (d.files.isEmpty()) Text("Brak plików do pobrania przez API — użyj \"Otwórz w przeglądarce\".")
            if (!inAppDownload && d.files.isNotEmpty()) {
                Text(
                    "${d.summary.source.displayName}: pobieranie przez stronę serwisu (brak klucza API lub API nie pozwala pobierać) — przyciski otworzą wbudowaną przeglądarkę, a pobrany plik trafi do Biblioteki.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            d.files.forEach { f -> FileRow(f, onDownload) }

            if (!d.descriptionHtml.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Opis", style = MaterialTheme.typography.titleMedium)
                Text(HtmlCompat.fromHtml(d.descriptionHtml, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim())
            }
            if (d.tags.isNotEmpty()) Text("Tagi: " + d.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileRow(f: ModelFile, onDownload: (ModelFile, Boolean) -> Unit) {
    val supporting = buildString {
        append(f.kind.label)
        f.sizeBytes?.let { append(" · %.1f MB".format(it / 1_048_576f)) }
        if (f.isLikelyForU1) append(" · profil U1")
        else if (f.kind == FileKind.GCODE) append(" · G-code innej drukarki? (sprawdzimy nagłówek)")
        if (f.kind == FileKind.BGCODE) append(" · binarny G-code Prusa — nieobsługiwany przez Klipper")
    }
    ListItem(
        headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row {
                IconButton(onClick = { onDownload(f, false) }) { Icon(Icons.Default.Download, "Pobierz") }
                if (f.kind.printable) {
                    OutlinedButton(onClick = { onDownload(f, true) }) {
                        Icon(Icons.Default.Print, null); Text(if (f.kind.needsSlicing) " Tnij i drukuj" else " Drukuj")
                    }
                }
            }
        },
    )
}

/** Wymiary: z API (rzadko) albo z analizy pobranego STL/3MF; ostrzeżenie, gdy model nie mieści się w U1. */
@Composable
private fun DimensionsBlock(d: ModelDetail, facts: ModelFacts?, onCheck: () -> Unit) {
    val vol = Dimensions.SNAPMAKER_U1
    val volText = "%.0f×%.0f×%.0f mm".format(vol.xMm, vol.yMm, vol.zMm)
    val dim = facts?.largestPart ?: d.dimensions
    val status = when {
        facts != null && facts.fit != FitStatus.UNKNOWN -> facts.fit
        d.dimensions != null -> if (d.dimensions.fitsIn(vol)) FitStatus.FITS else FitStatus.TOO_BIG
        else -> FitStatus.UNKNOWN
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            when (status) {
                FitStatus.TOO_BIG -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(" Model NIE mieści się w obszarze U1 ($volText) — przeskaluj lub podziel model.", color = MaterialTheme.colorScheme.error)
                }
                FitStatus.FITS -> Text("✓ Mieści się w obszarze U1 ($volText)")
                FitStatus.UNKNOWN -> Text("? Wymiary nieznane — serwis ich nie podaje.")
            }
            dim?.let { Text("Największy element: %.1f × %.1f × %.1f mm".format(it.xMm, it.yMm, it.zMm), style = MaterialTheme.typography.bodySmall) }
            when (facts?.u1Profile) {
                true -> Text("✓ Plik 3MF zawiera ustawienia Snapmaker U1" + (facts?.printerModel?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                false -> Text("3MF przygotowany dla: ${facts?.printerModel ?: "innej drukarki"} — wymaga ponownego cięcia dla U1", style = MaterialTheme.typography.bodySmall)
                null -> {}
            }
            if (status == FitStatus.UNKNOWN && d.files.any { GeometryAnalyzer.supports(it.kind) }) {
                OutlinedButton(onClick = onCheck) { Text("Pobierz i sprawdź wymiary") }
            }
        }
    }
}
