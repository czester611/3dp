package pl.u1companion.ui.print

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.u1companion.printer.job.Material
import pl.u1companion.printer.job.SupportMode
import pl.u1companion.printer.moonraker.ToolStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrintSetupScreen(libraryFileId: Long, onBack: () -> Unit, vm: PrintSetupViewModel = hiltViewModel()) {
    LaunchedEffect(libraryFileId) { vm.init(libraryFileId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val printers by vm.availablePrinters.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Przygotuj wydruk") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ui.file?.fileName ?: "Pobieranie pliku…", style = MaterialTheme.typography.titleMedium)
            if (ui.phase == PrintSetupUi.Phase.WaitingForFile) LinearProgressIndicator(Modifier.fillMaxWidth())

            ui.meta?.let { m ->
                Text(
                    listOfNotNull(m.slicer, m.printerModel?.let { "drukarka: $it" }, m.layerHeight?.let { "warstwa $it mm" }, m.infill?.let { "infill $it" }, m.estimatedTime)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ui.warnings.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(" $w", color = MaterialTheme.colorScheme.error)
                }
            }

            // 1. Drukarka
            Text("Drukarka", style = MaterialTheme.typography.titleSmall)
            if (printers.isEmpty()) Text("Brak zapisanych drukarek — dodaj w zakładce Drukarki.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                printers.forEach { p -> FilterChip(selected = ui.printer?.id == p.id, onClick = { vm.selectPrinter(p) }, label = { Text(p.name) }) }
            }
            ui.printerStatus?.let { s -> Text("Stan: ${s.printState} (Klipper: ${s.klippyState})", style = MaterialTheme.typography.bodySmall) }

            // 2. Głowice: mapowanie T(logiczne) -> głowica fizyczna + materiał/temperatura
            ui.config?.let { cfg ->
                Text("Głowice i materiał", style = MaterialTheme.typography.titleSmall)
                cfg.tools.forEach { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Narzędzie z pliku: T${t.logicalTool}", style = MaterialTheme.typography.labelLarge)
                            Text("Głowica w drukarce:", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0..3).forEach { phys ->
                                    val head = ui.printerStatus?.tools?.getOrNull(phys)
                                    FilterChip(
                                        selected = t.physicalTool == phys,
                                        onClick = { vm.setPhysicalTool(t.logicalTool, phys) },
                                        label = { HeadLabel(phys, head) },
                                    )
                                }
                            }
                            val head = ui.printerStatus?.tools?.getOrNull(t.physicalTool)
                            if (head?.filamentType != null && Material.fromText(head.filamentType) != t.material) {
                                Text(
                                    "Uwaga: w głowicy ${t.physicalTool + 1} jest ${head.filamentType}, a plik/ustawienia zakładają ${t.material.label}.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text("Materiał:", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Material.entries.forEach { m ->
                                    FilterChip(selected = t.material == m, onClick = { vm.setToolMaterial(t.logicalTool, m) }, label = { Text(m.label) })
                                }
                            }
                            Text("Temperatura dyszy: ${t.nozzleTemp} °C")
                            Slider(
                                value = t.nozzleTemp.toFloat(),
                                onValueChange = { vm.setToolTemp(t.logicalTool, it.toInt()) },
                                valueRange = t.material.nozzleRange.first.toFloat()..t.material.nozzleRange.last.toFloat(),
                            )
                        }
                    }
                }
                Text("Stół: ${cfg.bedTemp} °C")
                Slider(value = cfg.bedTemp.toFloat(), onValueChange = { v -> vm.updateConfig { it.copy(bedTemp = v.toInt()) } }, valueRange = 0f..100f)

                if (!ui.needsSlicing && ui.tempsEdited) {
                    Text(
                        "Plik jest już pocięty: zmiana materiału zmienia tylko temperatury w G-code. Prędkości, chłodzenie i retrakcja zostają z oryginału — dla pełnej zmiany materiału potnij model ponownie.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // 3. Parametry slicingu (tylko STL/3MF)
                if (ui.needsSlicing) {
                    Text("Slicing (serwer w LAN)", style = MaterialTheme.typography.titleSmall)
                    Text("Wysokość warstwy: ${"%.2f".format(cfg.layerHeightMm)} mm")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.08f, 0.12f, 0.16f, 0.2f, 0.24f, 0.28f).forEach { h ->
                            FilterChip(selected = cfg.layerHeightMm == h, onClick = { vm.updateConfig { it.copy(layerHeightMm = h) } }, label = { Text("$h") })
                        }
                    }
                    Text("Wypełnienie: ${cfg.infillPercent}%")
                    Slider(value = cfg.infillPercent.toFloat(), onValueChange = { v -> vm.updateConfig { it.copy(infillPercent = v.toInt()) } }, valueRange = 0f..100f, steps = 19)
                    Text("Podpory")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SupportMode.entries.forEach { sm ->
                            FilterChip(selected = cfg.supports == sm, onClick = { vm.updateConfig { it.copy(supports = sm) } }, label = { Text(when (sm) { SupportMode.NONE -> "Brak"; SupportMode.NORMAL -> "Normalne"; SupportMode.TREE -> "Drzewiaste" }) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Brim", Modifier.weight(1f))
                        Switch(checked = cfg.brim, onCheckedChange = { v -> vm.updateConfig { it.copy(brim = v) } })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Uruchom druk od razu po wysłaniu", Modifier.weight(1f))
                    Switch(checked = cfg.startImmediately, onCheckedChange = { v -> vm.updateConfig { it.copy(startImmediately = v) } })
                }
            }

            ui.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            if (ui.phase == PrintSetupUi.Phase.Slicing && ui.progress == null) CircularProgressIndicator()
            ui.message?.let { Text(it) }

            Spacer(Modifier.height(8.dp))
            val busy = ui.phase in setOf(PrintSetupUi.Phase.Slicing, PrintSetupUi.Phase.Uploading, PrintSetupUi.Phase.WaitingForFile)
            if (ui.needsSlicing) {
                Button(onClick = vm::slice, enabled = !busy && ui.config != null, modifier = Modifier.fillMaxWidth()) { Text("Potnij na serwerze") }
            } else {
                Button(
                    onClick = vm::sendAndPrint,
                    enabled = !busy && ui.printer != null && ui.gcode != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ui.config?.startImmediately != false) "Wyślij i drukuj" else "Wyślij na drukarkę") }
            }
        }
    }
}

@Composable
private fun HeadLabel(index: Int, head: ToolStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = head?.filamentColorRgba?.let { parseRgba(it) }
        if (color != null) Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Text(" ${index + 1}" + (head?.filamentType?.let { " $it" } ?: "") + if (head?.filamentLoaded == false) " (pusta)" else "")
    }
}

private fun parseRgba(hex: String): Color? = runCatching {
    val v = hex.removePrefix("#").toLong(16)
    if (hex.length >= 8) Color(((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
    else Color(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
}.getOrNull()
