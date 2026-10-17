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
) : ViewModel() {
    val state = MutableStateFlow<DetailState>(DetailState.Loading)
    val favoriteKeys: StateFlow<Set<String>> = library.favoriteKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    private val _openPrint = Channel<Long>(Channel.BUFFERED)
    val openPrint = _openPrint.receiveAsFlow()

    fun load(source: SourceId, id: String) = viewModelScope.launch {
        state.value = DetailState.Loading
        state.value = runCatching { repo.details(source, id) }
            .onSuccess { library.recordHistory(HistoryType.VIEWED, it.summary) }
            .fold({ DetailState.Ready(it) }, { DetailState.Error(it.message ?: "Błąd") })
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
    vm: DetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(source, modelId) { vm.load(source, modelId) }
    LaunchedEffect(Unit) { vm.openPrint.collect(onPrint) }
    val state by vm.state.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
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
            }
            is DetailState.Ready -> DetailContent(s.detail, Modifier.padding(pad), onDownload = { f, print -> vm.download(s.detail, f, print) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailContent(d: ModelDetail, modifier: Modifier, onDownload: (ModelFile, Boolean) -> Unit) {
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

            d.dimensions?.let { dim ->
                val fits = dim.fitsIn(Dimensions.SNAPMAKER_U1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!fits) Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text("Wymiary: %.0f × %.0f × %.0f mm — %s".format(dim.xMm, dim.yMm, dim.zMm, if (fits) "mieści się w U1" else "NIE mieści się w 270³ mm"))
                }
            }

            Text("Pliki", style = MaterialTheme.typography.titleMedium)
            if (d.files.isEmpty()) Text("Brak plików do pobrania przez API — użyj \"Otwórz w przeglądarce\".")
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
