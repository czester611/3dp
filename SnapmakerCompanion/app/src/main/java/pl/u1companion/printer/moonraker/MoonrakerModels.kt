package pl.u1companion.printer.moonraker

/** Stan drukarki znormalizowany dla UI (z obiektów Klippera). */
data class PrinterStatus(
    val klippyState: String = "unknown",          // ready / startup / shutdown / error
    val printState: String = "standby",           // standby / printing / paused / complete / cancelled / error
    val fileName: String? = null,
    val progress: Float = 0f,                     // virtual_sdcard.progress 0..1
    val printDurationSec: Double = 0.0,
    val currentLayer: Int? = null,
    val totalLayers: Int? = null,
    val bedTemp: Float = 0f,
    val bedTarget: Float = 0f,
    val activeTool: String? = null,               // toolhead.extruder: extruder / extruder1..3
    val tools: List<ToolStatus> = emptyList(),
    val cavityTemp: Float? = null,                // temperature_sensor cavity
    /** extruder_map_table z print_task_config: T(logiczne) -> głowica fizyczna. */
    val extruderMap: List<Int> = emptyList(),
    val message: String? = null,
)

/**
 * Stan jednej z 4 głowic U1. Dane filamentu z obiektu Klippera `print_task_config`
 * (fork u1-klipper: filament_type[4], filament_color_rgba[4], filament_exist[4], extruder_map_table[32]).
 */
data class ToolStatus(
    val index: Int,
    val temp: Float,
    val target: Float,
    val filamentType: String?,                    // np. "PLA", "PETG" (z RFID lub ustawione ręcznie)
    val filamentColorRgba: String?,
    val filamentLoaded: Boolean?,
    val filamentVendor: String? = null,           // np. "Snapmaker", "Generic"
    val filamentSubType: String? = null,          // np. "Basic", "Matte"
    /** print_task_config.extruders_used - głowica używana w bieżącym zadaniu. */
    val usedInCurrentJob: Boolean? = null,
)

data class UploadResult(val path: String, val printStarted: Boolean)

class MoonrakerException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** Obiekty i pola subskrybowane z Klippera (Moonraker printer.objects.subscribe/query). */
object U1Objects {
    val EXTRUDERS = listOf("extruder", "extruder1", "extruder2", "extruder3")

    val QUERY: Map<String, List<String>?> = buildMap {
        put("webhooks", listOf("state", "state_message"))
        put("print_stats", listOf("state", "filename", "print_duration", "info", "message"))
        put("virtual_sdcard", listOf("progress"))
        put("heater_bed", listOf("temperature", "target"))
        put("toolhead", listOf("extruder"))
        EXTRUDERS.forEach { put(it, listOf("temperature", "target")) }
        put("temperature_sensor cavity", listOf("temperature"))
        // Pola potwierdzone odczytem z drukarki (firmware 2.0.0, 2026-10-17).
        put(
            "print_task_config",
            listOf("filament_vendor", "filament_type", "filament_sub_type", "filament_color_rgba", "filament_exist", "extruder_map_table", "extruders_used"),
        )
    }
}
