package pl.u1companion.slicing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Kontrakt serwera slicingu w LAN (projekt: /home/piotrb/3dP/slicer-server).
 * Serwer opakowuje OrcaSlicer CLI (lub Snapmaker Orca) z profilami "Snapmaker U1".
 * Autoryzacja: nagłówek "Authorization: Bearer <token>" (wspólny sekret z Ustawień).
 */
interface SlicingApi {
    @GET("api/v1/health")
    suspend fun health(): HealthDto

    @GET("api/v1/profiles")
    suspend fun profiles(): ProfilesDto

    @Multipart
    @POST("api/v1/jobs")
    suspend fun createJob(
        @Part model: MultipartBody.Part,
        @Part("config") config: RequestBody,
    ): JobDto

    @GET("api/v1/jobs/{id}")
    suspend fun job(@Path("id") id: String): JobDto

    @Streaming
    @GET("api/v1/jobs/{id}/gcode")
    suspend fun gcode(@Path("id") id: String): ResponseBody

    @DELETE("api/v1/jobs/{id}")
    suspend fun delete(@Path("id") id: String)
}

@Serializable
data class HealthDto(
    val status: String,
    val version: String? = null,
    @SerialName("slicer_version") val slicerVersion: String? = null,
)

@Serializable
data class ProfilesDto(
    val printers: List<String> = emptyList(),
    val processes: List<String> = emptyList(),
    val filaments: List<String> = emptyList(),
)

/** Konfiguracja zlecenia wysyłana jako JSON w części "config". */
@Serializable
data class SliceConfigDto(
    @SerialName("printer_profile") val printerProfile: String = "Snapmaker U1 (0.4 nozzle)",
    @SerialName("process_profile") val processProfile: String? = null,
    /** Indeks = narzędzie logiczne (T0..T3); wartość = nazwa profilu filamentu w Orca. */
    @SerialName("filament_profiles") val filamentProfiles: List<String>,
    @SerialName("overrides") val overrides: Map<String, String> = emptyMap(),
    @SerialName("plate") val plate: Int = 1,
    /** true = ignoruj ustawienia zapisane w 3MF i użyj profili U1 (np. 3MF z MakerWorld dla Bambu). */
    @SerialName("reset_project_settings") val resetProjectSettings: Boolean = true,
    @SerialName("arrange") val arrange: Boolean = true,
)

@Serializable
data class JobDto(
    val id: String,
    val status: String,                       // queued | slicing | done | failed
    val progress: Float? = null,
    val error: String? = null,
    @SerialName("gcode_name") val gcodeName: String? = null,
    @SerialName("print_time_sec") val printTimeSec: Long? = null,
    @SerialName("filament_used_g") val filamentUsedG: List<Float>? = null,
)
