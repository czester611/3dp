package pl.u1companion.core.model

import kotlinx.serialization.Serializable

/** Serwisy z modelami. Kolejność = priorytet wdrażania (decyzja użytkownika). */
@Serializable
enum class SourceId(val displayName: String, val webBase: String) {
    PRINTABLES("Printables", "https://www.printables.com"),
    THINGIVERSE("Thingiverse", "https://www.thingiverse.com"),
    MAKERWORLD("MakerWorld", "https://makerworld.com"),
    THANGS("Thangs", "https://thangs.com"),
    MYMINIFACTORY("MyMiniFactory", "https://www.myminifactory.com"),
    CULTS3D("Cults3D", "https://cults3d.com"),
    CREALITY_CLOUD("Creality Cloud", "https://www.crealitycloud.com"),
    GRABCAD("GrabCAD", "https://grabcad.com"),
    CGTRADER("CGTrader", "https://www.cgtrader.com"),
    YEGGI("Yeggi", "https://www.yeggi.com"),
    DEMO("Demo (dane testowe)", "https://example.invalid"),
}

@Serializable
enum class SortOrder(val label: String) {
    RELEVANCE("Trafność"),
    POPULAR("Popularne"),
    NEWEST("Najnowsze"),
    MOST_DOWNLOADED("Najczęściej pobierane"),
    MOST_LIKED("Najbardziej lubiane"),
    TOP_RATED("Najwyżej oceniane"),
}

/** Znormalizowane rodziny licencji - każdy adapter mapuje swoje kody na tę enumerację. */
@Serializable
enum class LicenseFamily(val label: String, val commercial: Boolean, val derivatives: Boolean) {
    CC0("CC0 / domena publiczna", true, true),
    CC_BY("CC BY", true, true),
    CC_BY_SA("CC BY-SA", true, true),
    CC_BY_ND("CC BY-ND", true, false),
    CC_BY_NC("CC BY-NC", false, true),
    CC_BY_NC_SA("CC BY-NC-SA", false, true),
    CC_BY_NC_ND("CC BY-NC-ND", false, false),
    GPL("GPL/LGPL", true, true),
    OTHER_OPEN("Inna otwarta", true, true),
    STANDARD_DIGITAL("Licencja sklepu / prywatna", false, false),
    UNKNOWN("Nieznana", false, false);

    companion object {
        /** Heurystyka z kodu/nazwy licencji (np. "CC-BY-NC-SA", "Creative Commons — Attribution"). */
        fun fromText(text: String?): LicenseFamily {
            if (text.isNullOrBlank()) return UNKNOWN
            val t = text.uppercase()
                .replace("CREATIVE COMMONS", "CC")
                .replace("ATTRIBUTION", "BY")
                .replace("NONCOMMERCIAL", "NC")
                .replace("NON-COMMERCIAL", "NC")
                .replace("SHARE ALIKE", "SA")
                .replace("SHAREALIKE", "SA")
                .replace("NO DERIVATIVES", "ND")
                .replace("NODERIVATIVES", "ND")
            val nc = Regex("\\bNC\\b").containsMatchIn(t)
            val sa = Regex("\\bSA\\b").containsMatchIn(t)
            val nd = Regex("\\bND\\b").containsMatchIn(t)
            return when {
                "CC0" in t || "PUBLIC DOMAIN" in t -> CC0
                "GPL" in t -> GPL
                "BY" in t && nc && nd -> CC_BY_NC_ND
                "BY" in t && nc && sa -> CC_BY_NC_SA
                "BY" in t && nc -> CC_BY_NC
                "BY" in t && nd -> CC_BY_ND
                "BY" in t && sa -> CC_BY_SA
                "BY" in t -> CC_BY
                "STANDARD" in t || "PRIVATE" in t -> STANDARD_DIGITAL
                else -> OTHER_OPEN
            }
        }
    }
}

@Serializable
data class License(val code: String?, val name: String, val family: LicenseFamily)

@Serializable
enum class FileKind(val label: String, val printable: Boolean, val needsSlicing: Boolean) {
    STL("STL", true, true),
    THREE_MF("3MF", true, true), // może zawierać G-code (plate_N.gcode) - patrz ModelFile.embeddedGcode
    OBJ("OBJ", true, true),
    STEP("STEP", true, true),
    GCODE("G-code", true, false),
    BGCODE("Binary G-code (Prusa)", false, false), // nieobsługiwane przez Klipper
    ARCHIVE("ZIP", false, false),
    OTHER("Inny", false, false);

    companion object {
        fun fromFileName(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "stl" -> STL
            "3mf" -> THREE_MF
            "obj" -> OBJ
            "step", "stp" -> STEP
            "gcode", "gco", "g" -> GCODE
            "bgcode" -> BGCODE
            "zip", "rar", "7z" -> ARCHIVE
            else -> OTHER
        }
    }
}

@Serializable
data class Dimensions(val xMm: Float, val yMm: Float, val zMm: Float) {
    fun fitsIn(volume: Dimensions): Boolean {
        // Pozwalamy na obrót w osi Z (zamiana X/Y), bo slicer i tak może obrócić model.
        val direct = xMm <= volume.xMm && yMm <= volume.yMm
        val rotated = yMm <= volume.xMm && xMm <= volume.yMm
        return (direct || rotated) && zMm <= volume.zMm
    }

    companion object {
        /** Wolumen Snapmaker U1 wg profilu OrcaSlicer fdm_U1.json (printable_area 270x270, height 270). */
        val SNAPMAKER_U1 = Dimensions(270f, 270f, 270f)
    }
}

@Serializable
data class Author(val name: String, val profileUrl: String? = null, val avatarUrl: String? = null)

@Serializable
data class ModelSummary(
    val source: SourceId,
    val id: String,
    val title: String,
    val author: Author?,
    val thumbnailUrl: String?,
    val webUrl: String,
    val likes: Int? = null,
    val downloads: Int? = null,
    val publishedAtEpochMs: Long? = null,
    val license: License? = null,
    val isFree: Boolean = true,
    val priceCents: Int? = null,
    val category: String? = null,
    /** null = nie wiadomo bez pobrania szczegółów. */
    val hasPrintReadyFile: Boolean? = null,
    val hasU1Profile: Boolean? = null,
) {
    val key: String get() = "${source.name}:$id"
}

@Serializable
data class ModelFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val kind: FileKind,
    /** Np. "Snapmaker U1" jeśli nazwa/metadane wskazują profil dla U1. */
    val printerHint: String? = null,
    /** Kategoria pliku po stronie serwisu (np. Printables: stl/gcode/sla/other) potrzebna do pobrania. */
    val sourceFileType: String? = null,
) {
    val isLikelyForU1: Boolean
        get() = printerHint?.contains("U1", ignoreCase = true) == true ||
            Regex("(snapmaker[ _-]?u1|\\bu1\\b)", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

@Serializable
data class ModelDetail(
    val summary: ModelSummary,
    val descriptionHtml: String?,
    val images: List<String>,
    val files: List<ModelFile>,
    val tags: List<String> = emptyList(),
    val dimensions: Dimensions? = null,
)

@Serializable
data class Category(val id: String, val name: String, val parentId: String? = null)

@Serializable
data class SearchFilters(
    val sources: Set<SourceId> = setOf(SourceId.PRINTABLES),
    val categoryId: String? = null,
    val licenses: Set<LicenseFamily> = emptySet(),
    val onlyFree: Boolean = true,
    val onlyPrintReady: Boolean = false,
    val onlyU1Profile: Boolean = false,
    val onlyFitsU1: Boolean = false,
    val onlyCommercialUse: Boolean = false,
    val sort: SortOrder = SortOrder.POPULAR,
)

@Serializable
data class SearchQuery(val text: String = "", val filters: SearchFilters = SearchFilters())
