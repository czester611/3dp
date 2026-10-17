package pl.u1companion.slicing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import pl.u1companion.data.settings.SettingsRepository
import pl.u1companion.di.LanClient
import pl.u1companion.printer.job.PrintConfig
import pl.u1companion.printer.job.SupportMode
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SliceProgress {
    data class Uploading(val fraction: Float) : SliceProgress
    data class Slicing(val job: JobDto) : SliceProgress
    data class Done(val gcode: File, val job: JobDto) : SliceProgress
    data class Failed(val message: String) : SliceProgress
}

@Singleton
class SlicingRepository @Inject constructor(
    private val settings: SettingsRepository,
    @LanClient private val http: OkHttpClient,
    private val json: Json,
) {
    private suspend fun api(): SlicingApi {
        val s = settings.current()
        require(s.slicerServerUrl.isNotBlank()) { "Ustaw adres serwera slicingu w Ustawieniach" }
        val client = http.newBuilder().addInterceptor { chain ->
            val req = chain.request().newBuilder()
            if (s.slicerServerToken.isNotBlank()) req.header("Authorization", "Bearer ${s.slicerServerToken}")
            chain.proceed(req.build())
        }.build()
        return Retrofit.Builder()
            .baseUrl(s.slicerServerUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SlicingApi::class.java)
    }

    suspend fun health(): HealthDto = api().health()

    /** Mapowanie konfiguracji z UI na zlecenie dla serwera (nazwy kluczy Orca). */
    fun toSliceConfig(c: PrintConfig): SliceConfigDto {
        val filaments = c.tools.sortedBy { it.logicalTool }.map { "Snapmaker ${it.material.label}" } // TODO: lista z /profiles
        val overrides = buildMap {
            put("layer_height", c.layerHeightMm.toString())
            put("sparse_infill_density", "${c.infillPercent}%")
            put("enable_support", if (c.supports == SupportMode.NONE) "0" else "1")
            if (c.supports != SupportMode.NONE) put("support_type", if (c.supports == SupportMode.TREE) "tree(auto)" else "normal(auto)")
            put("brim_type", if (c.brim) "outer_only" else "no_brim")
            put("nozzle_temperature", c.tools.sortedBy { it.logicalTool }.joinToString(",") { it.nozzleTemp.toString() })
            put("hot_plate_temp", c.bedTemp.toString())
        }
        return SliceConfigDto(filamentProfiles = filaments, overrides = overrides, plate = c.plateIndex)
    }

    fun slice(model: File, config: PrintConfig, outDir: File): Flow<SliceProgress> = flow {
        val api = try { api() } catch (e: Exception) { emit(SliceProgress.Failed(e.message ?: "Błąd konfiguracji")); return@flow }
        emit(SliceProgress.Uploading(0f))
        val part = MultipartBody.Part.createFormData("model", model.name, model.asRequestBody("application/octet-stream".toMediaType()))
        val cfg = json.encodeToString(SliceConfigDto.serializer(), toSliceConfig(config)).toRequestBody("application/json".toMediaType())
        var job = try { api.createJob(part, cfg) } catch (e: Exception) { emit(SliceProgress.Failed("Serwer slicingu: ${e.message}")); return@flow }
        while (job.status == "queued" || job.status == "slicing") {
            emit(SliceProgress.Slicing(job))
            delay(1500)
            job = api.job(job.id)
        }
        if (job.status != "done") { emit(SliceProgress.Failed(job.error ?: "Slicing nieudany")); return@flow }
        val out = File(outDir, job.gcodeName ?: (model.nameWithoutExtension + ".gcode"))
        withContext(Dispatchers.IO) {
            api.gcode(job.id).byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        emit(SliceProgress.Done(out, job))
    }
}
