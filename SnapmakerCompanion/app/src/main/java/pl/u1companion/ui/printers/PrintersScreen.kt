package pl.u1companion.ui.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.u1companion.data.local.PrinterEntity
import pl.u1companion.printer.PrinterRepository
import pl.u1companion.printer.discovery.DiscoveredPrinter
import pl.u1companion.printer.moonraker.PrinterStatus
import javax.inject.Inject

@HiltViewModel
class PrintersViewModel @Inject constructor(private val repo: PrinterRepository) : ViewModel() {
    val saved = repo.printers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val discovered = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val scanning = MutableStateFlow(false)
    val selected = MutableStateFlow<PrinterEntity?>(null)
    val status = MutableStateFlow<PrinterStatus?>(null)
    val message = MutableStateFlow<String?>(null)
    private var statusJob: Job? = null

    fun scan() = viewModelScope.launch {
        scanning.value = true
        discovered.value = emptyList()
        withTimeoutOrNull(10_000) {
            repo.discover().collect { d -> discovered.value = (discovered.value.filter { it.id != d.id } + d) }
        }
        scanning.value = false
    }

    fun add(d: DiscoveredPrinter) = viewModelScope.launch {
        runCatching { repo.addVerified(d) }.onFailure { message.value = "Moonraker nie odpowiada na ${d.host}:${d.moonrakerPort}: ${it.message}" }
    }

    fun addManual(host: String, port: Int, apiKey: String) = viewModelScope.launch {
        runCatching { repo.addManual(host.trim(), port, apiKey) }.onFailure { message.value = "Nie można połączyć: ${it.message}" }
    }

    fun remove(id: String) = viewModelScope.launch { repo.remove(id) }

    fun select(p: PrinterEntity) {
        selected.value = p
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            repo.status(p).catch { message.value = it.message }.collect { status.value = it }
        }
    }

    fun cameraUrl(p: PrinterEntity) = repo.client(p).cameraSnapshotUrl()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersScreen(vm: PrintersViewModel = hiltViewModel()) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (saved.isEmpty()) vm.scan() }
    LaunchedEffect(saved) { if (selected == null) saved.firstOrNull()?.let(vm::select) }

    Scaffold(topBar = { TopAppBar(title = { Text("Drukarki") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Zapisane", style = MaterialTheme.typography.titleSmall)
            saved.forEach { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.host}:${p.port}" + (p.firmwareVersion?.let { " · fw $it" } ?: "")) },
                    trailingContent = { IconButton(onClick = { vm.remove(p.id) }) { Icon(Icons.Default.Delete, "Usuń") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.select(p) }) { Text("Pokaż stan") }
            }

            selected?.let { p -> status?.let { s -> StatusCard(p, s, vm.cameraUrl(p)) } }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.scan() }, enabled = !scanning) { Text(if (scanning) "Szukam…" else "Wyszukaj w sieci (mDNS)") }
                OutlinedButton(onClick = { manual = true }) { Text("Dodaj ręcznie") }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            discovered.filter { d -> saved.none { it.id == d.id } }.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name + if (d.isSnapmakerU1) " (Snapmaker U1)" else "") },
                    supportingContent = { Text("${d.host} · ${d.serviceType} · sn ${d.txt["sn"] ?: "?"}") },
                    trailingContent = { TextButton(onClick = { vm.add(d) }) { Text("Dodaj") } },
                )
            }
            if (!scanning && discovered.isEmpty()) {
                Text(
                    "Nie znaleziono drukarek. Sprawdź, czy telefon jest w tej samej sieci Wi-Fi (bez izolacji klientów) albo dodaj adres IP ręcznie (port 7125).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (manual) {
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("7125") }
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Dodaj drukarkę") },
            text = {
                Column {
                    OutlinedTextField(host, { host = it }, label = { Text("Adres IP / host") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port Moonraker") }, singleLine = true)
                    OutlinedTextField(key, { key = it }, label = { Text("Klucz API (opcjonalnie)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.addManual(host, port.toIntOrNull() ?: 7125, key); manual = false }) { Text("Dodaj") } },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun StatusCard(p: PrinterEntity, s: PrinterStatus, cameraUrl: String) {
    // Kamera: polling snapshotu (stock firmware nie udostępnia streamu MJPEG/WebRTC).
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.id) { while (true) { delay(5_000); tick++ } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            AsyncImage(model = "$cameraUrl?t=$tick", contentDescription = "Kamera", modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text("Stan: ${s.printState} · Klipper: ${s.klippyState}")
            s.fileName?.let { Text("Plik: $it", style = MaterialTheme.typography.bodySmall) }
            if (s.printState == "printing" || s.printState == "paused") {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                Text("%.1f%%".format(s.progress * 100) + (s.currentLayer?.let { " · warstwa $it/${s.totalLayers ?: "?"}" } ?: ""))
            }
            Text("Stół: %.0f/%.0f °C".format(s.bedTemp, s.bedTarget) + (s.cavityTemp?.let { " · komora %.0f °C".format(it) } ?: ""))
            s.tools.forEach { t ->
                val active = s.activeTool == (if (t.index == 0) "extruder" else "extruder${t.index}")
                Text(
                    "Głowica ${t.index + 1}${if (active) " (aktywna)" else ""}: %.0f/%.0f °C · ".format(t.temp, t.target) +
                        listOfNotNull(t.filamentVendor, t.filamentType, t.filamentSubType).joinToString(" ").ifBlank { "brak danych filamentu" } +
                        (if (t.filamentLoaded == false) " · pusta" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // TODO(etap 2): pauza / wznów / anuluj (z potwierdzeniem), SET_PAUSE_AT_LAYER
        }
    }
}
