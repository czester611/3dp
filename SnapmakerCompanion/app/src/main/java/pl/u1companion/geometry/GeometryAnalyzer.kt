package pl.u1companion.geometry

import pl.u1companion.core.model.Dimensions
import pl.u1companion.core.model.FileKind
import java.io.File

enum class FitStatus { FITS, TOO_BIG, UNKNOWN }

/** Wynik analizy jednego pobranego pliku. */
data class FileGeometry(
    val largestPart: Dimensions?,
    val overall: Dimensions?,
    val fitsU1: Boolean?,
    /** true = 3MF ma ustawienia/G-code dla Snapmaker U1; false = ustawienia innej drukarki; null = brak danych. */
    val u1Profile: Boolean?,
    val printerModel: String?,
    val embeddedGcodes: List<String>,
    val error: String? = null,
)

object GeometryAnalyzer {

    /** Jedyne miejsce z wolumenem roboczym - patrz [Dimensions.SNAPMAKER_U1]. */
    val buildVolume: Dimensions get() = Dimensions.SNAPMAKER_U1

    fun supports(kind: FileKind) = kind == FileKind.STL || kind == FileKind.THREE_MF

    fun analyze(file: File, kind: FileKind, volume: Dimensions = buildVolume): FileGeometry = try {
        when (kind) {
            FileKind.STL -> {
                val d = StlParser.boundingBox(file).toDimensions()
                FileGeometry(d, d, d?.fitsIn(volume), null, null, emptyList())
            }
            FileKind.THREE_MF -> {
                val info = ThreeMfInspector.inspect(file)
                val largest = info.largestItem
                val hasSettings = info.printerModel != null || info.printerSettingsId != null
                FileGeometry(
                    largestPart = largest,
                    overall = info.overall,
                    // Elementy można drukować osobno -> decyduje każdy element z osobna.
                    fitsU1 = if (info.items.isNotEmpty()) info.items.all { it.fitsIn(volume) } else largest?.fitsIn(volume),
                    u1Profile = if (hasSettings) info.looksLikeU1 else null,
                    printerModel = info.printerModel ?: info.printerSettingsId,
                    embeddedGcodes = info.embeddedGcodes,
                )
            }
            else -> FileGeometry(null, null, null, null, null, emptyList(), error = "Nieobsługiwany typ ${kind.label}")
        }
    } catch (e: Exception) {
        FileGeometry(null, null, null, null, null, emptyList(), error = e.message ?: e.javaClass.simpleName)
    }

    /**
     * Status modelu z wielu plików: TOO_BIG gdy którykolwiek przeanalizowany plik się nie mieści,
     * FITS gdy wszystkie przeanalizowane się mieszczą, UNKNOWN gdy brak analiz.
     */
    fun aggregateFit(fits: List<Boolean?>): FitStatus = when {
        fits.any { it == false } -> FitStatus.TOO_BIG
        fits.any { it == true } -> FitStatus.FITS
        else -> FitStatus.UNKNOWN
    }

    /** Profil U1 modelu: true gdy którykolwiek plik ma profil U1; false gdy są tylko profile innych drukarek. */
    fun aggregateU1(flags: List<Boolean?>): Boolean? = when {
        flags.any { it == true } -> true
        flags.any { it == false } -> false
        else -> null
    }
}

/** Fakty o modelu znane po analizie pobranych plików (agregat z [pl.u1companion.data.local.ModelGeometryEntity]). */
data class ModelFacts(
    val fit: FitStatus,
    val u1Profile: Boolean?,
    val largestPart: Dimensions?,
    val printerModel: String? = null,
)
