package pl.u1companion.geometry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import pl.u1companion.core.model.Dimensions
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Wynik analizy pliku 3MF. */
@Serializable
data class ThreeMfInfo(
    /** Wymiary każdego elementu <build><item> (po transformacji). */
    val items: List<Dimensions>,
    /** Wymiary całego układu na płycie (wszystkie elementy razem). */
    val overall: Dimensions?,
    /** Z Metadata/project_settings.config (Orca/Bambu/Snapmaker Orca). */
    val printerModel: String? = null,
    val printerSettingsId: String? = null,
    val filamentTypes: List<String> = emptyList(),
    /** Pocięte płyty osadzone w 3MF, np. "Metadata/plate_1.gcode". */
    val embeddedGcodes: List<String> = emptyList(),
) {
    /** Największy element - do oceny "czy mieści się" (elementy można rozłożyć na kilka wydruków). */
    val largestItem: Dimensions? get() = items.maxByOrNull { maxOf(it.xMm, it.yMm, it.zMm) } ?: overall

    val looksLikeU1: Boolean
        get() = listOfNotNull(printerModel, printerSettingsId).any { U1_REGEX.containsMatchIn(it) }

    companion object {
        val U1_REGEX = Regex("snapmaker\\s*u1|(?<![a-z0-9])u1(?![a-z0-9])", RegexOption.IGNORE_CASE)
    }
}

/**
 * Analiza 3MF (ZIP): geometria z pliki .model w katalogu 3D (rdzeń 3MF + rozszerzenie Production: komponenty w osobnych
 * plikach przez atrybut p:path - tak zapisują OrcaSlicer/Bambu Studio), ustawienia z Metadata/project_settings.config.
 *
 * Transformacja 3MF: "m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32", punkt (x,y,z) ->
 * (x*m00 + y*m10 + z*m20 + m30, x*m01 + y*m11 + z*m21 + m31, x*m02 + y*m12 + z*m22 + m32).
 * Dla obróconych elementów przekształcamy narożniki bboxa -> wynik zachowawczy (może być nieco większy).
 */
object ThreeMfInspector {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PLATE_GCODE = Regex("^Metadata/plate_\\d+\\.gcode$")

    fun inspect(file: File): ThreeMfInfo = ZipFile(file).use { zip ->
        val root = rootModelPath(zip)
        val models = HashMap<String, Map<String, ObjectDef>>()
        val builds = HashMap<String, List<Ref>>()

        fun load(path: String): Map<String, ObjectDef> = models.getOrPut(path) {
            val entry = zip.getEntry(path.trimStart('/')) ?: throw IOException("Brak $path w 3MF")
            val (objs, build) = zip.getInputStream(entry).reader(Charsets.UTF_8).use { parseModel(it) }
            builds[path] = build
            objs
        }

        val boxCache = HashMap<String, BoundingBox>()
        fun objectBox(path: String, id: String, depth: Int = 0): BoundingBox = boxCache.getOrPut("$path#$id") {
            val def = load(path)[id] ?: return@getOrPut BoundingBox()
            val box = BoundingBox()
            def.mesh?.let(box::add)
            if (depth < 16) def.components.forEach { c ->
                box.add(transform(objectBox(c.path ?: path, c.objectId, depth + 1), c.transform))
            }
            box
        }

        load(root)
        val items = builds[root].orEmpty().map { item -> transform(objectBox(item.path ?: root, item.objectId), item.transform) }
        val overall = BoundingBox().apply { items.forEach(::add) }

        val settings = zip.getEntry("Metadata/project_settings.config")?.let { e ->
            runCatching { lenientJson.parseToJsonElement(zip.getInputStream(e).reader().use { it.readText() }) as? JsonObject }.getOrNull()
        }
        fun str(k: String) = (settings?.get(k) as? JsonPrimitive)?.contentOrNull
            ?: ((settings?.get(k) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
        val filaments = (settings?.get("filament_type") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        ThreeMfInfo(
            items = items.mapNotNull { it.toDimensions() },
            overall = overall.toDimensions(),
            printerModel = str("printer_model"),
            printerSettingsId = str("printer_settings_id"),
            filamentTypes = filaments,
            embeddedGcodes = zip.entries().asSequence().map { it.name }.filter { PLATE_GCODE.matches(it) }.sorted().toList(),
        )
    }

    /** Wypakowuje osadzony G-code płyty (np. 3MF pocięty w Snapmaker Orca dla U1). */
    fun extractGcode(file: File, entryName: String, target: File): File = ZipFile(file).use { zip ->
        val e = zip.getEntry(entryName) ?: throw IOException("Brak $entryName")
        zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
        target
    }

    // --- parsowanie ---

    internal data class Ref(val objectId: String, val transform: DoubleArray?, val path: String?)
    internal class ObjectDef(val mesh: BoundingBox?, val components: List<Ref>)

    private fun rootModelPath(zip: ZipFile): String {
        val rels = zip.getEntry("_rels/.rels") ?: return "/3D/3dmodel.model"
        var target: String? = null
        zip.getInputStream(rels).reader().use { r ->
            MiniXml.scan(r) { _, name, attrs ->
                if (name.endsWith("Relationship") && attrs["Type"]?.endsWith("/3dmodel") == true && target == null) target = attrs["Target"]
            }
        }
        return target?.let { if (it.startsWith("/")) it else "/$it" } ?: "/3D/3dmodel.model"
    }

    internal fun parseModel(reader: java.io.Reader): Pair<Map<String, ObjectDef>, List<Ref>> {
        val objects = HashMap<String, ObjectDef>()
        val build = ArrayList<Ref>()
        var curId: String? = null
        var curMesh: BoundingBox? = null
        var curComps = ArrayList<Ref>()
        MiniXml.scan(reader) { kind, rawName, a ->
            val name = rawName.substringAfter(':') // ignorujemy prefiksy przestrzeni nazw
            when {
                name == "object" && kind != MiniXml.Kind.END -> {
                    curId = a["id"]; curMesh = null; curComps = ArrayList()
                    if (kind == MiniXml.Kind.EMPTY) { curId?.let { objects[it] = ObjectDef(null, emptyList()) }; curId = null }
                }
                name == "object" && kind == MiniXml.Kind.END -> {
                    curId?.let { objects[it] = ObjectDef(curMesh, curComps) }
                    curId = null
                }
                name == "vertex" && curId != null -> {
                    val box = curMesh ?: BoundingBox().also { curMesh = it }
                    box.add(a["x"]?.toDoubleOrNull() ?: Double.NaN, a["y"]?.toDoubleOrNull() ?: Double.NaN, a["z"]?.toDoubleOrNull() ?: Double.NaN)
                }
                name == "component" && curId != null ->
                    a["objectid"]?.let { curComps += Ref(it, parseTransform(a["transform"]), pathAttr(a)) }
                name == "item" && kind != MiniXml.Kind.END ->
                    a["objectid"]?.let { build += Ref(it, parseTransform(a["transform"]), pathAttr(a)) }
            }
        }
        return objects to build
    }

    private fun pathAttr(a: Map<String, String>): String? =
        a.entries.firstOrNull { (k, _) -> k == "path" || k.endsWith(":path") }?.value

    internal fun parseTransform(t: String?): DoubleArray? {
        if (t.isNullOrBlank()) return null
        val v = t.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        return if (v.size == 12) v.toDoubleArray() else null
    }

    internal fun transform(box: BoundingBox, m: DoubleArray?): BoundingBox {
        if (m == null || box.isEmpty) return box
        val out = BoundingBox()
        box.corners().forEach { (x, y, z) ->
            out.add(
                x * m[0] + y * m[3] + z * m[6] + m[9],
                x * m[1] + y * m[4] + z * m[7] + m[10],
                x * m[2] + y * m[5] + z * m[8] + m[11],
            )
        }
        return out
    }
}
