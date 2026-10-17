package pl.u1companion.printer.job

import kotlinx.serialization.Serializable

@Serializable
enum class Material(val label: String, val nozzleDefault: Int, val bedDefault: Int, val nozzleRange: IntRange) {
    PLA("PLA", 220, 60, 190..235),
    PETG("PETG", 245, 75, 225..260),
    TPU("TPU", 225, 45, 210..240),
    ABS("ABS", 255, 100, 240..270),
    ASA("ASA", 255, 100, 240..270),
    PLA_CF("PLA-CF", 225, 60, 200..240),
    PETG_CF("PETG-CF", 250, 75, 230..265),
    PVA("PVA (podpory)", 210, 60, 190..225),
    ;

    companion object {
        fun fromText(t: String?): Material? = t?.trim()?.uppercase()?.replace(' ', '-')?.let { u ->
            entries.firstOrNull { it.name.replace('_', '-') == u || it.label.uppercase() == u }
                ?: entries.firstOrNull { u.startsWith(it.name) }
        }
    }
}

/** Ustawienia jednej głowicy w zleceniu. */
@Serializable
data class ToolConfig(
    /** Narzędzie logiczne w G-code / 3MF (T0..). */
    val logicalTool: Int,
    /** Fizyczna głowica U1 (0..3). */
    val physicalTool: Int,
    val material: Material,
    val nozzleTemp: Int,
    val colorRgba: String? = null,
)

@Serializable
enum class SupportMode { NONE, NORMAL, TREE }

/**
 * "Minimalna konfiguracja" przed drukiem.
 * Pola oznaczone (slice) działają tylko, gdy plik jest cięty (STL/3MF -> serwer slicingu).
 * Dla gotowego G-code można zmienić tylko temperatury i mapowanie głowic (patrz GcodePatcher).
 */
@Serializable
data class PrintConfig(
    val tools: List<ToolConfig>,
    val bedTemp: Int,
    val layerHeightMm: Float = 0.2f,      // (slice)
    val infillPercent: Int = 15,           // (slice)
    val supports: SupportMode = SupportMode.NONE, // (slice)
    val brim: Boolean = false,             // (slice)
    val plateIndex: Int = 1,               // (slice) numer płyty w 3MF
    val autoBedLeveling: Boolean = false,
    val startImmediately: Boolean = true,
)
