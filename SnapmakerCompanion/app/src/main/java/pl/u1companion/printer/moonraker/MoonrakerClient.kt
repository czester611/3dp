package pl.u1companion.printer.moonraker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Klient Moonraker (HTTP + WebSocket JSON-RPC) dla jednej drukarki.
 *
 * Endpointy (standard Moonraker, obecne w forku Snapmaker/u1-moonraker - sprawdzone w kodzie):
 *  - GET  /server/info, /printer/info
 *  - POST /server/files/upload  (multipart: file, root=gcodes, path, print=true|false)
 *    Fork U1 akceptuje w root "gcodes" rozszerzenia: .gcode .g .gco .ufp (VALID_GCODE_EXTS) - NIE .3mf!
 *  - POST /printer/print/start?filename=..., /printer/print/pause|resume|cancel
 *  - POST /printer/gcode/script?script=...
 *  - POST /printer/objects/query  {"objects": {...}}
 *  - WS   /websocket -> printer.objects.subscribe, notify_status_update
 *
 * Autoryzacja: domyślny moonraker.conf U1 ma trusted_clients 10.0.0.0/8, 172.16.0.0/12, 192.0.0.0/8 itd.,
 * więc w typowym LAN klucz API nie jest potrzebny. Gdy jest - nagłówek X-Api-Key.
 */
class MoonrakerClient(
    host: String,
    port: Int,
    private val apiKey: String?,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val base: HttpUrl = "http://$host:$port/".toHttpUrl()
    private val wsUrl = "ws://$host:$port/websocket"

    suspend fun serverInfo(): JsonObject = get("server/info")

    /** Metadane pliku z Moonrakera (slicer, filament_type "PETG;PLA;..", nozzle_temp[], layer_height, estimated_time...). */
    suspend fun fileMetadata(path: String): JsonObject = get("server/files/metadata", "filename" to path)

    /**
     * Kamera: na stock firmware 2.0.0 /server/webcams/list jest puste, a /webcam/ (nginx :80) zwraca 502.
     * Dostępny jest tylko snapshot JPG w root "camera" -> UI odpytuje go cyklicznie (polling).
     * Uwaga: w teście plik nie odświeżał się co kilka sekund - częstotliwość aktualizacji do ustalenia.
     */
    fun cameraSnapshotUrl(): String = base.newBuilder().addPathSegments("server/files/camera/monitor.jpg").build().toString()
    suspend fun printerInfo(): JsonObject = get("printer/info")

    suspend fun queryStatus(): PrinterStatus {
        val body = buildJsonObject { put("objects", objectsJson()) }
        val res = post("printer/objects/query", body)
        return parseStatus(res["status"]?.jsonObject ?: JsonObject(emptyMap()), PrinterStatus())
    }

    /**
     * Wysyła plik G-code. [remoteName] trafia do katalogu "gcodes/[subdir]".
     * [startPrint]=true uruchamia druk od razu po uploadzie (parametr print=true).
     */
    suspend fun upload(
        file: File,
        remoteName: String = file.name,
        subdir: String = "",                // pliki ze Snapmaker Orca lądują w katalogu głównym gcodes
        startPrint: Boolean = false,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult = withContext(Dispatchers.IO) {
        require(remoteName.substringAfterLast('.').lowercase() in setOf("gcode", "g", "gco", "ufp")) {
            "Drukarka przyjmuje tylko .gcode/.g/.gco/.ufp (plik 3MF trzeba najpierw pociąć)"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("root", "gcodes")
            .apply { if (subdir.isNotBlank()) addFormDataPart("path", subdir) }
            .addFormDataPart("print", startPrint.toString())
            .addFormDataPart("file", remoteName, ProgressBody(file, "application/octet-stream".toMediaType(), onProgress))
            .build()
        val req = request("server/files/upload").post(body).build()
        val obj = execute(req)
        val result = (obj["result"] as? JsonObject) ?: obj
        val item = result["item"]?.jsonObject
        UploadResult(
            path = item?.get("path")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: if (subdir.isBlank()) remoteName else "$subdir/$remoteName",
            printStarted = (result["print_started"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    suspend fun startPrint(path: String) {
        post("printer/print/start", null, "filename" to path)
    }

    suspend fun pause() { post("printer/print/pause", null) }
    suspend fun resume() { post("printer/print/resume", null) }
    suspend fun cancel() { post("printer/print/cancel", null) }

    /** Wysyła skrypt G-code (np. SET_PRINT_EXTRUDER_MAP, M104). Blokuje do wykonania przez Klippera. */
    suspend fun runGcode(script: String) {
        post("printer/gcode/script", null, "script" to script)
    }

    /**
     * Mapowanie logicznego narzędzia z G-code (T0..T31) na fizyczną głowicę (0..3).
     * Komenda z forka u1-klipper (print_task_config.py); odrzucana w trakcie druku.
     * DO WERYFIKACJI na urządzeniu: czy PRINT_START z profilu Orca nie nadpisuje mapy.
     */
    suspend fun setExtruderMap(logicalTool: Int, physicalTool: Int) =
        runGcode("SET_PRINT_EXTRUDER_MAP CONFIG_EXTRUDER=$logicalTool MAP_EXTRUDER=$physicalTool")

    /** Strumień statusu przez WebSocket (subskrypcja obiektów; aktualizacje różnicowe są scalane). */
    fun statusUpdates(): Flow<PrinterStatus> = callbackFlow {
        val ids = AtomicInteger(1)
        var current = PrinterStatus()
        val req = Request.Builder().url(wsUrl).apply { apiKey?.let { header("X-Api-Key", it) } }.build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val msg = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "printer.objects.subscribe")
                    put("params", buildJsonObject { put("objects", objectsJson()) })
                    put("id", ids.getAndIncrement())
                }
                webSocket.send(msg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val status: JsonObject? = when {
                    // odpowiedź na subscribe: {"result": {"status": {...}}}
                    obj["result"] is JsonObject -> (obj["result"] as JsonObject)["status"] as? JsonObject
                    // {"method":"notify_status_update","params":[{...}, eventtime]}
                    (obj["method"] as? JsonPrimitive)?.contentOrNull == "notify_status_update" ->
                        (obj["params"] as? JsonArray)?.firstOrNull() as? JsonObject
                    (obj["method"] as? JsonPrimitive)?.contentOrNull == "notify_klippy_disconnected" -> {
                        current = current.copy(klippyState = "disconnected"); trySend(current); null
                    }
                    else -> null
                }
                if (status != null) {
                    current = parseStatus(status, current)
                    trySend(current)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(MoonrakerException("WebSocket: ${t.message}", response?.code, t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { ws.close(1000, null) }
    }

    // --- parsowanie ---

    private fun objectsJson(): JsonObject = buildJsonObject {
        U1Objects.QUERY.forEach { (k, fields) ->
            if (fields == null) put(k, JsonNull) else put(k, buildJsonArray { fields.forEach { add(JsonPrimitive(it)) } })
        }
    }

    /** Scala (częściową) aktualizację z poprzednim stanem. */
    internal fun parseStatus(s: JsonObject, prev: PrinterStatus): PrinterStatus {
        fun JsonObject.obj(k: String) = this[k] as? JsonObject
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

        var st = prev
        s.obj("webhooks")?.let { w -> st = st.copy(klippyState = w.str("state") ?: st.klippyState, message = w.str("state_message") ?: st.message) }
        s.obj("print_stats")?.let { p ->
            val info = p.obj("info")
            st = st.copy(
                printState = p.str("state") ?: st.printState,
                fileName = p.str("filename") ?: st.fileName,
                printDurationSec = p.num("print_duration") ?: st.printDurationSec,
                currentLayer = info?.let { (it["current_layer"] as? JsonPrimitive)?.intOrNull } ?: st.currentLayer,
                totalLayers = info?.let { (it["total_layer"] as? JsonPrimitive)?.intOrNull } ?: st.totalLayers,
            )
        }
        s.obj("virtual_sdcard")?.num("progress")?.let { st = st.copy(progress = it.toFloat()) }
        s.obj("heater_bed")?.let { b -> st = st.copy(bedTemp = b.num("temperature")?.toFloat() ?: st.bedTemp, bedTarget = b.num("target")?.toFloat() ?: st.bedTarget) }
        s.obj("toolhead")?.str("extruder")?.let { st = st.copy(activeTool = it) }

        val ptc = s.obj("print_task_config")
        fun arr(k: String) = ptc?.get(k) as? JsonArray
        val tools = U1Objects.EXTRUDERS.mapIndexed { i, name ->
            val old = prev.tools.getOrNull(i)
            val e = s.obj(name)
            ToolStatus(
                index = i,
                temp = e?.num("temperature")?.toFloat() ?: old?.temp ?: 0f,
                target = e?.num("target")?.toFloat() ?: old?.target ?: 0f,
                filamentType = (arr("filament_type")?.getOrNull(i) as? JsonPrimitive)?.contentOrNull?.takeIf { it != "NONE" } ?: old?.filamentType,
                filamentColorRgba = (arr("filament_color_rgba")?.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: old?.filamentColorRgba,
                filamentLoaded = (arr("filament_exist")?.getOrNull(i) as? JsonPrimitive)?.booleanOrNull ?: old?.filamentLoaded,
                filamentVendor = (arr("filament_vendor")?.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: old?.filamentVendor,
                filamentSubType = (arr("filament_sub_type")?.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: old?.filamentSubType,
                usedInCurrentJob = (arr("extruders_used")?.getOrNull(i) as? JsonPrimitive)?.booleanOrNull ?: old?.usedInCurrentJob,
            )
        }
        val map = arr("extruder_map_table")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: prev.extruderMap
        val cavity = s.obj("temperature_sensor cavity")?.num("temperature")?.toFloat() ?: prev.cavityTemp
        return st.copy(tools = tools, extruderMap = map, cavityTemp = cavity)
    }

    // --- HTTP ---

    private fun request(path: String, vararg query: Pair<String, String>): Request.Builder {
        val url = base.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url).apply { apiKey?.let { header("X-Api-Key", it) } }
    }

    private suspend fun get(path: String, vararg query: Pair<String, String>): JsonObject = withContext(Dispatchers.IO) {
        unwrap(execute(request(path, *query).get().build()))
    }

    private suspend fun post(path: String, body: JsonElement?, vararg query: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val rb = (body?.toString() ?: "").toRequestBody("application/json".toMediaType())
            unwrap(execute(request(path, *query).post(rb).build()))
        }

    private fun unwrap(obj: JsonObject): JsonObject = (obj["result"] as? JsonObject) ?: obj

    private fun execute(req: Request): JsonObject = try {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.let { (it as JsonPrimitive).content }
                }.getOrNull()
                throw MoonrakerException(msg ?: "HTTP ${resp.code}", resp.code)
            }
            if (text.isBlank()) JsonObject(emptyMap()) else {
                val el = json.parseToJsonElement(text)
                (el as? JsonObject) ?: buildJsonObject { put("result", el) }
            }
        }
    } catch (e: IOException) {
        throw MoonrakerException("Brak połączenia z drukarką: ${e.message}", cause = e)
    }

    /** RequestBody z raportowaniem postępu wysyłania. */
    private class ProgressBody(
        private val file: File,
        private val type: MediaType,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            file.source().use { src ->
                while (true) {
                    val read = src.read(sink.buffer, 64 * 1024L)
                    if (read == -1L) break
                    sent += read
                    sink.flush()
                    onProgress(sent, total)
                }
            }
        }
    }
}
