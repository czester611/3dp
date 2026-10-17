package pl.u1companion.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId
import pl.u1companion.core.model.FileKind
import pl.u1companion.data.local.LocalFileStatus
import pl.u1companion.data.local.SavedSearchEntity
import pl.u1companion.data.repo.LibraryRepository
import pl.u1companion.download.DownloadRepository
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    downloads: DownloadRepository,
) : ViewModel() {
    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.state() = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = library.favorites.state()
    val files = downloads.files.state()
    val history = library.history.state()
    val searches = library.savedSearches.state()

    fun decode(e: SavedSearchEntity): SearchQuery = library.decodeSearch(e)
    fun deleteSearch(id: Long) = viewModelScope.launch { library.deleteSearch(id) }
    fun markRun(id: Long) = viewModelScope.launch { library.markSearchRun(id) }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenModel: (SourceId, String) -> Unit,
    onPrintFile: (Long) -> Unit,
    onRunSearch: (SearchQuery) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Ulubione", "Pliki", "Historia", "Wyszukiwania")
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val searches by vm.searches.collectAsStateWithLifecycle()
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(topBar = { TopAppBar(title = { Text("Biblioteka") }) }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            LazyColumn {
                when (tab) {
                    0 -> items(favorites, key = { it.key }) { f ->
                        ListItem(
                            headlineContent = { Text(f.title) },
                            supportingContent = { Text("${f.source} · ${f.author ?: ""}") },
                            leadingContent = { AsyncImage(f.thumbnailUrl, null, Modifier.size(56.dp)) },
                            modifier = Modifier.clickable { onOpenModel(SourceId.valueOf(f.source), f.modelId) },
                        )
                    }
                    1 -> items(files, key = { it.id }) { f ->
                        ListItem(
                            headlineContent = { Text(f.fileName) },
                            supportingContent = { Text("${runCatching { FileKind.valueOf(f.kind).label }.getOrDefault(f.kind)} · ${f.status}" + (f.error?.let { " · $it" } ?: "")) },
                            trailingContent = {
                                if (f.status == LocalFileStatus.READY) TextButton(onClick = { onPrintFile(f.id) }) { Text("Drukuj") }
                            },
                        )
                    }
                    2 -> {
                        item { TextButton(onClick = vm::clearHistory) { Text("Wyczyść historię") } }
                        items(history, key = { it.id }) { h ->
                            ListItem(
                                headlineContent = { Text(h.title) },
                                supportingContent = { Text("${h.type} · ${df.format(Date(h.at))}" + (h.detail?.let { " · $it" } ?: "")) },
                                modifier = Modifier.clickable(enabled = h.modelKey != null) {
                                    h.modelKey?.split(":", limit = 2)?.let { (s, id) -> onOpenModel(SourceId.valueOf(s), id) }
                                },
                            )
                        }
                    }
                    else -> items(searches, key = { it.id }) { s ->
                        val q = vm.decode(s)
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = { Text("\"${q.text}\" · ${q.filters.sources.joinToString { it.displayName }} · ${q.filters.sort.label}") },
                            trailingContent = { IconButton(onClick = { vm.deleteSearch(s.id) }) { Icon(Icons.Default.Delete, "Usuń") } },
                            modifier = Modifier.clickable { vm.markRun(s.id); onRunSearch(q) },
                        )
                    }
                }
            }
        }
    }
}
