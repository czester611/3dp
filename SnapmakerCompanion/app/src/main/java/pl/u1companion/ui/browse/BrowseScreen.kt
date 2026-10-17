package pl.u1companion.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import pl.u1companion.core.model.CanonicalCategory
import pl.u1companion.core.model.Category
import pl.u1companion.core.model.Dimensions
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.source.AccessMode
import pl.u1companion.data.source.ClientSideFilter
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.WebFallbackPolicy
import pl.u1companion.geometry.FitStatus
import pl.u1companion.geometry.ModelFacts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenModel: (ModelSummary) -> Unit,
    onOpenWebSource: (SourceId, String) -> Unit,
    initialQuery: pl.u1companion.core.model.SearchQuery? = null,
    onOpenSettings: () -> Unit = {},
    vm: BrowseViewModel = hiltViewModel(),
) {
    LaunchedEffect(initialQuery) { initialQuery?.let(vm::applySaved) }
    val query by vm.query.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val activeFilters by vm.activeFilterCount.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val trees by vm.categoryTrees.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshAvailability() } // np. po powrocie z Ustawień z nowym kluczem
    val items = vm.results.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var showFilters by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.warnings.collect { w -> snackbar.showSnackbar("${w.source.displayName}: ${w.error.message}") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Odkrywaj modele") },
                actions = {
                    IconButton(onClick = { showSave = true }) { Icon(Icons.Default.Bookmark, "Zapisz wyszukiwanie") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query.text,
                onValueChange = vm::setText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Szukaj: np. organizer, benchy, uchwyt…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.text.isNotEmpty()) IconButton(onClick = { vm.setText("") }) { Icon(Icons.Default.Clear, "Wyczyść") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            // Wiersz źródeł (wyniki zagregowane)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(vm.searchableSources, key = { it.id }) { src ->
                    val configured = availability[src.id] != false
                    // Serwis z API bez klucza: chip nie jest martwy - oferuje przeglądarkę lub ustawienie klucza.
                    val needsKey = src.capabilities.requiresCredential && !configured
                    var menu by remember { mutableStateOf(false) }
                    Box {
                    FilterChip(
                        selected = src.id in query.filters.sources && !needsKey,
                        onClick = { if (needsKey) menu = true else vm.toggleSource(src.id) },
                        label = { Text(src.id.displayName + accessSuffix(src, configured)) },
                        leadingIcon = {
                            val icon = when {
                                src.id in query.filters.sources -> Icons.Default.Check
                                src.capabilities.requiresCredential -> Icons.Default.Key
                                src.capabilities.access == AccessMode.UNOFFICIAL_API -> Icons.Default.Science
                                else -> null
                            }
                            icon?.let { Icon(it, null, Modifier.size(16.dp)) }
                        },
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Otwórz ${src.id.displayName} w przeglądarce") },
                            leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                            onClick = { menu = false; onOpenWebSource(src.id, src.webSearchUrl(query.text)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Ustaw klucz API (wyniki w aplikacji)") },
                            leadingIcon = { Icon(Icons.Default.Key, null) },
                            onClick = { menu = false; onOpenSettings() },
                        )
                    }
                    }
                }
                // Serwisy bez API: otwierane w wbudowanej przeglądarce z bieżącą frazą
                items(vm.webSources.filter { WebFallbackPolicy.offerWeb(it, availability[it.id] == true) }, key = { "web-" + it.id }) { src ->
                    AssistChip(
                        onClick = { onOpenWebSource(src.id, src.webSearchUrl(query.text)) },
                        label = { Text(src.id.displayName) },
                        leadingIcon = { Icon(Icons.Default.OpenInBrowser, "przez przeglądarkę", Modifier.size(16.dp)) },
                    )
                }
            }

            // Szybkie filtry + sortowanie
            QuickFilterRow(
                filters = query.filters,
                activeFilterCount = activeFilters,
                onSort = vm::setSort,
                onToggleFree = { vm.updateFilters { f -> f.copy(onlyFree = !f.onlyFree) } },
                onTogglePrintReady = { vm.updateFilters { f -> f.copy(onlyPrintReady = !f.onlyPrintReady) } },
                onToggleU1 = { vm.updateFilters { f -> f.copy(onlyU1Profile = !f.onlyU1Profile) } },
                onToggleFits = { vm.updateFilters { f -> f.copy(onlyFitsU1 = !f.onlyFitsU1) } },
                onClearCategory = { vm.setCategory(null) },
                onOpenFilters = { vm.loadCategories(); showFilters = true },
            )
            NotesBar(notes.map { it.text })

            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(count = items.itemCount, key = items.itemKey { it.key }) { idx ->
                        items[idx]?.let { m ->
                            ModelCard(m, facts[m.key], isFavorite = m.key in favorites, onClick = { onOpenModel(m) }, onFavorite = { vm.toggleFavorite(m) })
                        }
                    }
                    // Stopka: ładowanie / błąd / koniec + serwisy "przez przeglądarkę"
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            when (val s = items.loadState.append) {
                                is LoadState.Loading -> CircularProgressIndicator()
                                is LoadState.Error -> OutlinedButton(onClick = { items.retry() }) { Text("Błąd: ${s.error.message} — ponów") }
                                else -> if (items.itemCount > 0) Text("To już wszystkie wyniki", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(12.dp))
                            // Wszystkie serwisy, których nie da się teraz użyć przez API (+ te bez pobierania w aplikacji)
                            WebSourcesFooter(
                                vm.webSources.filter { WebFallbackPolicy.offerWeb(it, availability[it.id] == true) || !it.capabilities.inAppDownload },
                                query.text, onOpenWebSource,
                            )
                        }
                    }
                }
                when (val r = items.loadState.refresh) {
                    is LoadState.Loading -> if (items.itemCount == 0) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is LoadState.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nie udało się pobrać wyników", style = MaterialTheme.typography.titleMedium)
                        Text(r.error.message ?: "", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { items.retry() }) { Text("Ponów") }
                    }
                    else -> if (items.itemCount == 0) Text("Brak wyników dla wybranych filtrów", Modifier.align(Alignment.Center))
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FilterSheetContent(
                filters = query.filters,
                sources = vm.searchableSources,
                trees = trees,
                notes = notes.map { it.text },
                onSelectCategory = vm::setCategory,
                onSelectSourceCategory = vm::setSourceCategory,
                onToggleSource = vm::toggleSource,
                onToggleLicense = vm::toggleLicense,
                onUpdate = vm::updateFilters,
                onReset = vm::resetFilters,
                onDone = { showFilters = false },
            )
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf(query.text) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Zapisz wyszukiwanie") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nazwa") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.saveCurrentSearch(name); showSave = false }) { Text("Zapisz") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun QuickFilterRow(
    filters: SearchFilters,
    activeFilterCount: Int,
    onSort: (SortOrder) -> Unit,
    onToggleFree: () -> Unit,
    onTogglePrintReady: () -> Unit,
    onToggleU1: () -> Unit,
    onToggleFits: () -> Unit,
    onClearCategory: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            BadgedBox(badge = { if (activeFilterCount > 0) Badge { Text("$activeFilterCount") } }) {
                AssistChip(onClick = onOpenFilters, label = { Text("Filtry") }, leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(18.dp)) })
            }
        }
        item {
            Box {
                AssistChip(onClick = { sortOpen = true }, label = { Text(filters.sort.label) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) })
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortOrder.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { onSort(s); sortOpen = false })
                    }
                }
            }
        }
        item { FilterChip(selected = filters.onlyFree, onClick = onToggleFree, label = { Text("Darmowe") }) }
        item { FilterChip(selected = filters.onlyPrintReady, onClick = onTogglePrintReady, label = { Text("Z G-code/3MF") }) }
        item { FilterChip(selected = filters.onlyU1Profile, onClick = onToggleU1, label = { Text("Profil U1") }) }
        item { FilterChip(selected = filters.onlyFitsU1, onClick = onToggleFits, label = { Text("Mieści się ${volumeLabel()}") }) }
        val catLabel = filters.sourceCategory?.let { "${it.source.displayName}: ${it.name}" } ?: filters.category?.label
        if (catLabel != null) item {
            InputChip(
                selected = true, onClick = onClearCategory, label = { Text(catLabel, maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Clear, "Usuń kategorię", Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun ModelCard(m: ModelSummary, facts: ModelFacts?, isFavorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = m.thumbnailUrl,
                contentDescription = m.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            )
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Ulubione", tint = MaterialTheme.colorScheme.primary)
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            ) { Text(m.source.displayName, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
        }
        Column(Modifier.padding(8.dp)) {
            Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(m.author?.name ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.likes?.let { Icon(Icons.Default.ThumbUp, null, Modifier.size(12.dp)); Text(compact(it), style = MaterialTheme.typography.labelSmall) }
                m.downloads?.let { Icon(Icons.Default.Download, null, Modifier.size(12.dp)); Text(compact(it), style = MaterialTheme.typography.labelSmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                m.license?.let { Tag(it.code ?: it.family.label) }
                if (!m.isFree) Tag("Płatny")
                if (ClientSideFilter.hasU1Profile(m, facts)) Tag("U1") else if (m.hasPrintReadyFile == true) Tag("G-code")
                // Wymiary znane dopiero po pobraniu i analizie pliku: ✓ mieści się / ✗ za duży / ? nieznane
                when (facts?.fit ?: FitStatus.UNKNOWN) {
                    FitStatus.FITS -> Tag("✓ ${volumeLabel()}")
                    FitStatus.TOO_BIG -> Tag("✗ ${volumeLabel()}")
                    FitStatus.UNKNOWN -> Tag("? ${volumeLabel()}")
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebSourcesFooter(sources: List<ModelSource>, text: String, onOpen: (SourceId, String) -> Unit) {
    Text("Szukaj także w serwisach bez API (wbudowana przeglądarka):", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEach { s ->
            AssistChip(
                onClick = { onOpen(s.id, s.webSearchUrl(text)) },
                label = { Text(s.id.displayName) },
                leadingIcon = { Icon(Icons.Default.OpenInBrowser, null, Modifier.size(16.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheetContent(
    filters: SearchFilters,
    sources: List<ModelSource>,
    trees: List<SourceCategoryTree>,
    notes: List<String>,
    onSelectCategory: (CanonicalCategory?) -> Unit,
    onSelectSourceCategory: (SourceId, Category) -> Unit,
    onToggleSource: (SourceId) -> Unit,
    onToggleLicense: (LicenseFamily) -> Unit,
    onUpdate: ((SearchFilters) -> SearchFilters) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Filtry", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        SectionTitle("Źródła")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { s ->
                FilterChip(
                    selected = s.id in filters.sources,
                    onClick = { onToggleSource(s.id) },
                    label = {
                        Text(s.id.displayName + accessSuffix(s, configured = true, long = true))
                    },
                )
            }
        }

        SectionTitle("Sortowanie")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SortOrder.entries.forEach { s ->
                FilterChip(selected = filters.sort == s, onClick = { onUpdate { it.copy(sort = s) } }, label = { Text(s.label) })
            }
        }

        // Adnotacje: sortowanie zastąpione najbliższym odpowiednikiem / kategoria nieobsługiwana
        notes.forEach { Text("≈ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }

        SectionTitle("Kategoria")
        CategorySection(filters, trees, onSelectCategory, onSelectSourceCategory)

        SectionTitle("Licencja")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LicenseFamily.entries.filter { it != LicenseFamily.UNKNOWN }.forEach { l ->
                FilterChip(selected = l in filters.licenses, onClick = { onToggleLicense(l) }, label = { Text(l.label) })
            }
        }
        SwitchRow("Tylko z prawem użytku komercyjnego", filters.onlyCommercialUse) { v -> onUpdate { it.copy(onlyCommercialUse = v) } }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("Druk")
        SwitchRow("Tylko darmowe", filters.onlyFree) { v -> onUpdate { it.copy(onlyFree = v) } }
        SwitchRow("Tylko z gotowym G-code / 3MF", filters.onlyPrintReady) { v -> onUpdate { it.copy(onlyPrintReady = v) } }
        SwitchRow("Tylko z profilem Snapmaker U1", filters.onlyU1Profile) { v -> onUpdate { it.copy(onlyU1Profile = v) } }
        SwitchRow("Mieści się w ${volumeLabel(long = true)}", filters.onlyFitsU1) { v -> onUpdate { it.copy(onlyFitsU1 = v) } }
        Text(
            "Wymiary są znane po pobraniu pliku STL/3MF (analiza w tle) — modele z nieznanymi wymiarami zostają na liście z oznaczeniem \"?\". Profil U1: nazwa pliku lub ustawienia drukarki zapisane w 3MF.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReset) { Text("Wyczyść") }
            Button(onClick = onDone, modifier = Modifier.width(160.dp)) { Text("Pokaż wyniki") }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fk".format(n / 1_000f)
    else -> n.toString()
}

@Composable
private fun NotesBar(notes: List<String>) {
    if (notes.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 2.dp)) {
        val shown = if (expanded) notes else notes.take(1)
        shown.forEach { Text("≈ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis) }
        if (!expanded && notes.size > 1) Text("+${notes.size - 1} uwag…", style = MaterialTheme.typography.labelSmall)
    }
}

/** Kategorie: wspólne (kanoniczne, działają we wszystkich serwisach) + drzewo natywne każdego serwisu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySection(
    filters: SearchFilters,
    trees: List<SourceCategoryTree>,
    onSelectCategory: (CanonicalCategory?) -> Unit,
    onSelectSourceCategory: (SourceId, Category) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filters.category == null && filters.sourceCategory == null, onClick = { onSelectCategory(null) }, label = { Text("Wszystkie") })
        CanonicalCategory.entries.forEach { c ->
            FilterChip(
                selected = filters.sourceCategory == null && filters.category == c,
                onClick = { onSelectCategory(c) },
                label = { Text(c.label) },
            )
        }
    }
    if (trees.isEmpty()) {
        Text("Wybrane serwisy nie udostępniają drzewa kategorii (lub brak sieci) — działa filtr wspólny.", style = MaterialTheme.typography.bodySmall)
    }
    trees.forEach { tree ->
        var expandedRoot by remember(tree.source) { mutableStateOf<String?>(null) }
        Text("Kategorie ${tree.source.displayName}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tree.roots.forEach { (root, children) ->
                val selected = filters.sourceCategory?.let { it.source == tree.source && it.id == root.id } == true
                FilterChip(
                    selected = selected || expandedRoot == root.id,
                    onClick = {
                        onSelectSourceCategory(tree.source, root)
                        expandedRoot = if (expandedRoot == root.id) null else root.id
                    },
                    label = { Text(root.name + if (children.isNotEmpty()) " ▾" else "") },
                )
            }
        }
        val children = tree.roots.firstOrNull { it.first.id == expandedRoot }?.second.orEmpty()
        if (children.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp)) {
                children.forEach { c ->
                    FilterChip(
                        selected = filters.sourceCategory?.let { it.source == tree.source && it.id == c.id } == true,
                        onClick = { onSelectSourceCategory(tree.source, c) },
                        label = { Text(c.name) },
                    )
                }
            }
        }
    }
}

/** Etykieta wolumenu z jednej stałej (Dimensions.SNAPMAKER_U1). */
private fun volumeLabel(long: Boolean = false): String {
    val v = Dimensions.SNAPMAKER_U1
    val cube = v.xMm == v.yMm && v.yMm == v.zMm
    return if (long || !cube) "%.0f×%.0f×%.0f mm".format(v.xMm, v.yMm, v.zMm) else "%.0f³".format(v.xMm)
}

/** Oznaczenie trybu dostępu źródła na chipie. */
private fun accessSuffix(src: ModelSource, configured: Boolean, long: Boolean = false): String {
    val c = src.capabilities
    return when {
        c.requiresCredential && !configured -> " · ↗ przeglądarka"
        long && c.access == AccessMode.UNOFFICIAL_API -> " (nieoficjalne API)"
        long && c.requiresCredential -> " (oficjalne API, klucz)" + if (!c.inAppDownload) ", pobieranie przez stronę" else ""
        else -> ""
    }
}
