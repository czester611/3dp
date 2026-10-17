package pl.u1companion.printer

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import pl.u1companion.data.local.AppDatabase
import pl.u1companion.data.local.PrintJobEntity
import pl.u1companion.data.local.PrinterEntity
import pl.u1companion.di.LanClient
import pl.u1companion.printer.discovery.DiscoveredPrinter
import pl.u1companion.printer.discovery.PrinterDiscovery
import pl.u1companion.printer.job.GcodePatcher
import pl.u1companion.printer.job.PrintConfig
import pl.u1companion.printer.moonraker.MoonrakerClient
import pl.u1companion.printer.moonraker.PrinterStatus
import pl.u1companion.printer.moonraker.UploadResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterRepository @Inject constructor(
    private val db: AppDatabase,
    private val discovery: PrinterDiscovery,
    @LanClient private val http: OkHttpClient,
    private val json: Json,
) {
    val printers: Flow<List<PrinterEntity>> = db.printers().observeAll()
    val jobs = db.printJobs().observeRecent()

    private val clients = ConcurrentHashMap<String, MoonrakerClient>()

    fun discover(): Flow<DiscoveredPrinter> = discovery.discover()

    fun client(p: PrinterEntity): MoonrakerClient =
        clients.getOrPut("${p.id}@${p.host}:${p.port}") { MoonrakerClient(p.host, p.port, p.apiKey, http, json) }

    /** Zapisuje drukarkę po weryfikacji, że Moonraker odpowiada (/server/info). */
    suspend fun addVerified(d: DiscoveredPrinter): PrinterEntity {
        val tmp = MoonrakerClient(d.host, d.moonrakerPort, null, http, json)
        tmp.serverInfo() // rzuca MoonrakerException gdy brak odpowiedzi
        val e = PrinterEntity(
            id = d.id, name = d.name, host = d.host, port = d.moonrakerPort,
            model = d.txt["machine_type"], firmwareVersion = d.txt["version"],
            lastSeenAt = System.currentTimeMillis(),
        )
        db.printers().upsert(e)
        return e
    }

    suspend fun addManual(host: String, port: Int, apiKey: String?): PrinterEntity {
        val c = MoonrakerClient(host, port, apiKey?.ifBlank { null }, http, json)
        c.serverInfo()
        val e = PrinterEntity(id = "$host:$port", name = "Drukarka $host", host = host, port = port, apiKey = apiKey?.ifBlank { null }, lastSeenAt = System.currentTimeMillis(), manuallyAdded = true)
        db.printers().upsert(e)
        return e
    }

    suspend fun remove(id: String) = db.printers().delete(id)

    suspend fun get(id: String) = db.printers().get(id)

    fun status(p: PrinterEntity): Flow<PrinterStatus> = client(p).statusUpdates()

    /**
     * Wysyłka gotowego G-code + start:
     *  1. (opcjonalnie) patch temperatur -> plik tymczasowy,
     *  2. SET_PRINT_EXTRUDER_MAP dla każdej głowicy (przed startem; firmware odrzuca w trakcie druku),
     *  3. upload z print=true (lub upload + osobny start).
     */
    suspend fun sendAndPrint(
        printer: PrinterEntity,
        gcode: File,
        config: PrintConfig,
        tempOverrides: GcodePatcher.TempOverrides?,
        workDir: File,
        modelKey: String?,
        onProgress: (Float) -> Unit,
    ): UploadResult {
        val c = client(printer)
        val toSend = if (tempOverrides != null) {
            File(workDir, gcode.nameWithoutExtension + "_u1.gcode").also { GcodePatcher.patch(gcode, it, tempOverrides) }
        } else gcode

        config.tools.forEach { t -> c.setExtruderMap(t.logicalTool, t.physicalTool) }

        val res = c.upload(toSend, startPrint = config.startImmediately) { sent, total ->
            onProgress(if (total > 0) sent.toFloat() / total else 0f)
        }
        db.printJobs().insert(
            PrintJobEntity(
                printerId = printer.id, remoteFileName = res.path, libraryFileId = null, modelKey = modelKey,
                configJson = json.encodeToString(PrintConfig.serializer(), config),
                startedAt = System.currentTimeMillis(), state = if (res.printStarted) "printing" else "uploaded",
            ),
        )
        return res
    }
}
