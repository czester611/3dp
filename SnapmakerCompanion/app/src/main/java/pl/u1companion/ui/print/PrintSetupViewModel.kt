package pl.u1companion.ui.print

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.u1companion.core.model.FileKind
import pl.u1companion.data.local.AppDatabase
import pl.u1companion.data.local.LibraryFileEntity
import pl.u1companion.data.local.LocalFileStatus
import pl.u1companion.data.local.PrinterEntity
import pl.u1companion.geometry.ThreeMfInspector
import pl.u1companion.printer.PrinterRepository
import pl.u1companion.printer.job.GcodeInspector
import pl.u1companion.printer.job.GcodeMeta
import pl.u1companion.printer.job.GcodePatcher
import pl.u1companion.printer.job.Material
import pl.u1companion.printer.job.PrintConfig
import pl.u1companion.printer.job.ToolConfig
import pl.u1companion.printer.moonraker.PrinterStatus
import pl.u1companion.slicing.SliceProgress
import pl.u1companion.slicing.SlicingRepository
import java.io.File
import javax.inject.Inject

data class PrintSetupUi(
    val file: LibraryFileEntity? = null,
    val meta: GcodeMeta? = null,
    val printer: PrinterEntity? = null,
    val printerStatus: PrinterStatus? = null,
    val config: PrintConfig? = null,
    /** Plik G-code gotowy do wysłania (oryginał albo wynik slicingu). */
    val gcode: File? = null,
    val tempsEdited: Boolean = false,
    val phase: Phase = Phase.WaitingForFile,
    val progress: Float? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
) {
    enum class Phase { WaitingForFile, Configure, Slicing, Uploading, Started, Error }
    val needsSlicing: Boolean get() = file?.let { FileKind.valueOf(it.kind).needsSlicing } == true && gcode == null
}

@HiltViewModel
class PrintSetupViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
    private val printers: PrinterRepository,
    private val slicing: SlicingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PrintSetupUi())
    val ui: StateFlow<PrintSetupUi> = _ui

    val availablePrinters: StateFlow<List<PrinterEntity>> =
        printers.printers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var statusJob: Job? = null

    fun init(libraryFileId: Long) {
        if (_ui.value.file != null) return
        viewModelScope.launch {
            // Czekamy aż DownloadWorker skończy pobieranie.
            val f = db.libraryFiles().observeAll()
                .map { list -> list.firstOrNull { it.id == libraryFileId } }
                .filterNotNull()
                .first { it.status == LocalFileStatus.READY || it.status == LocalFileStatus.FAILED }
            if (f.status == LocalFileStatus.FAILED) {
                _ui.update { it.copy(file = f, phase = PrintSetupUi.Phase.Error, message = "Pobieranie nieudane: ${f.error}") }
                return@launch
            }
            val kind = FileKind.valueOf(f.kind)
            // 3MF pocięty dla U1 (np. w Snapmaker Orca) zawiera Metadata/plate_N.gcode - można drukować bez slicingu.
            val embedded: File? = if (kind == FileKind.THREE_MF) withContext(Dispatchers.IO) {
                runCatching {
                    val info = ThreeMfInspector.inspect(File(f.localPath!!))
                    val entry = info.embeddedGcodes.firstOrNull()
                    if (entry != null && info.looksLikeU1) {
                        val out = File(ctx.filesDir, "sliced").apply { mkdirs() }
                        ThreeMfInspector.extractGcode(File(f.localPath), entry, File(out, f.fileName.substringBeforeLast('.') + "_" + entry.substringAfterLast('/')))
                    } else null
                }.getOrNull()
            } else null
            val gcodeFile = embedded ?: if (kind == FileKind.GCODE) File(f.localPath!!) else null
            val meta = gcodeFile?.let { withContext(Dispatchers.IO) { GcodeInspector.inspect(it) } }
            val warnings = buildList {
                if (meta?.looksForeign == true) add("Plik pocięty dla \"${meta.printerModel}\", nie dla Snapmaker U1 — druk może się nie udać lub uszkodzić wydruk.")
                if (meta?.isKlipperFlavor == false) add("G-code nie jest w wariancie Klipper.")
                if (kind == FileKind.BGCODE) add("Binarny G-code (Prusa) nie jest obsługiwany.")
            }
            _ui.update {
                it.copy(
                    file = f, meta = meta, warnings = warnings,
                    gcode = gcodeFile,
                    message = embedded?.let { "Użyto G-code osadzonego w 3MF (profil U1): ${it.name}" },
                    config = defaultConfig(meta),
                    phase = PrintSetupUi.Phase.Configure,
                )
            }
            availablePrinters.first { it.isNotEmpty() }.firstOrNull()?.let(::selectPrinter)
        }
    }

    private fun defaultConfig(meta: GcodeMeta?): PrintConfig {
        val used = meta?.usedTools?.ifEmpty { null } ?: setOf(0)
        val tools = used.sorted().map { t ->
            val mat = Material.fromText(meta?.filamentTypes?.getOrNull(t)) ?: Material.PLA
            ToolConfig(logicalTool = t, physicalTool = t.coerceIn(0, 3), material = mat, nozzleTemp = meta?.nozzleTemps?.getOrNull(t) ?: mat.nozzleDefault)
        }
        return PrintConfig(tools = tools, bedTemp = meta?.bedTemp ?: tools.first().material.bedDefault, layerHeightMm = meta?.layerHeight ?: 0.2f)
    }

    fun selectPrinter(p: PrinterEntity) {
        _ui.update { it.copy(printer = p) }
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            printers.status(p)
                .catch { e -> _ui.update { it.copy(message = "Drukarka: ${e.message}") } }
                .collect { s -> _ui.update { it.copy(printerStatus = s) } }
        }
    }

    fun updateConfig(block: (PrintConfig) -> PrintConfig) = _ui.update { it.copy(config = it.config?.let(block)) }

    /** Zmiana materiału głowicy ustawia domyślne temperatury materiału. */
    fun setToolMaterial(logical: Int, m: Material) = _ui.update { s ->
        s.copy(
            tempsEdited = true,
            config = s.config?.copy(tools = s.config.tools.map { if (it.logicalTool == logical) it.copy(material = m, nozzleTemp = m.nozzleDefault) else it }),
        )
    }

    fun setToolTemp(logical: Int, temp: Int) = _ui.update { s ->
        s.copy(tempsEdited = true, config = s.config?.copy(tools = s.config.tools.map { if (it.logicalTool == logical) it.copy(nozzleTemp = temp) else it }))
    }

    fun setPhysicalTool(logical: Int, physical: Int) = updateConfig { c ->
        c.copy(tools = c.tools.map { if (it.logicalTool == logical) it.copy(physicalTool = physical) else it })
    }

    fun slice() {
        val s = _ui.value
        val f = s.file ?: return
        val cfg = s.config ?: return
        viewModelScope.launch {
            val out = File(ctx.filesDir, "sliced").apply { mkdirs() }
            slicing.slice(File(f.localPath!!), cfg, out).collect { p ->
                when (p) {
                    is SliceProgress.Uploading -> _ui.update { it.copy(phase = PrintSetupUi.Phase.Slicing, progress = p.fraction, message = "Wysyłanie modelu do serwera…") }
                    is SliceProgress.Slicing -> _ui.update { it.copy(progress = p.job.progress, message = "Slicing (${p.job.status})…") }
                    is SliceProgress.Done -> _ui.update {
                        it.copy(gcode = p.gcode, meta = GcodeInspector.inspect(p.gcode), tempsEdited = false, phase = PrintSetupUi.Phase.Configure, progress = null, message = "Gotowe: ${p.gcode.name}")
                    }
                    is SliceProgress.Failed -> _ui.update { it.copy(phase = PrintSetupUi.Phase.Error, message = p.message) }
                }
            }
        }
    }

    fun sendAndPrint() {
        val s = _ui.value
        val printer = s.printer ?: return
        val gcode = s.gcode ?: return
        val cfg = s.config ?: return
        if (s.printerStatus?.printState in setOf("printing", "paused")) {
            _ui.update { it.copy(message = "Drukarka jest zajęta (${s.printerStatus?.printState}).") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(phase = PrintSetupUi.Phase.Uploading, progress = 0f, message = "Wysyłanie do ${printer.name}…") }
            runCatching {
                val overrides = if (s.tempsEdited) GcodePatcher.TempOverrides(cfg.tools.associate { it.logicalTool to it.nozzleTemp }, cfg.bedTemp) else null
                withContext(Dispatchers.IO) {
                    printers.sendAndPrint(printer, gcode, cfg, overrides, File(ctx.cacheDir, "upload").apply { mkdirs() }, s.file?.modelKey) { p ->
                        _ui.update { it.copy(progress = p) }
                    }
                }
            }.onSuccess { r ->
                _ui.update { it.copy(phase = PrintSetupUi.Phase.Started, progress = null, message = if (r.printStarted) "Druk uruchomiony: ${r.path}" else "Wysłano: ${r.path}") }
            }.onFailure { e ->
                _ui.update { it.copy(phase = PrintSetupUi.Phase.Error, progress = null, message = e.message) }
            }
        }
    }
}
