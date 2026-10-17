package pl.u1companion.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import pl.u1companion.core.model.Dimensions
import pl.u1companion.core.model.FileKind
import pl.u1companion.data.local.AppDatabase
import pl.u1companion.data.local.LibraryFileEntity
import pl.u1companion.data.local.ModelGeometryEntity
import pl.u1companion.geometry.FileGeometry
import pl.u1companion.geometry.GeometryAnalyzer
import pl.u1companion.geometry.ModelFacts
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Analiza geometrii pobranych plików + cache w Room + agregat faktów per model. */
@Singleton
class GeometryRepository @Inject constructor(private val db: AppDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** modelKey -> fakty (aktualizowane po każdej analizie). Używane przez listę i filtr "mieści się". */
    val factsByModel: StateFlow<Map<String, ModelFacts>> = db.geometry().observeAll()
        .map { rows -> rows.filter { it.modelKey != null }.groupBy { it.modelKey!! }.mapValues { (_, r) -> aggregate(r) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun factsFor(modelKey: String): Flow<ModelFacts?> =
        db.geometry().observeForModel(modelKey).map { if (it.isEmpty()) null else aggregate(it) }

    /** Analizuje plik STL/3MF (wywoływane po pobraniu). Zwraca null dla nieobsługiwanych typów. */
    suspend fun analyze(file: LibraryFileEntity): FileGeometry? = withContext(Dispatchers.IO) {
        val kind = runCatching { FileKind.valueOf(file.kind) }.getOrNull() ?: return@withContext null
        val path = file.localPath ?: return@withContext null
        if (!GeometryAnalyzer.supports(kind)) return@withContext null
        val g = GeometryAnalyzer.analyze(File(path), kind)
        db.geometry().upsert(
            ModelGeometryEntity(
                libraryFileId = file.id, modelKey = file.modelKey, fileName = file.fileName,
                partX = g.largestPart?.xMm, partY = g.largestPart?.yMm, partZ = g.largestPart?.zMm,
                overallX = g.overall?.xMm, overallY = g.overall?.yMm, overallZ = g.overall?.zMm,
                fitsU1 = g.fitsU1, u1Profile = g.u1Profile, printerModel = g.printerModel,
                embeddedGcodes = g.embeddedGcodes.joinToString("\n").ifEmpty { null },
                error = g.error, analyzedAt = System.currentTimeMillis(),
            ),
        )
        g
    }

    companion object {
        fun aggregate(rows: List<ModelGeometryEntity>): ModelFacts {
            val ok = rows.filter { it.error == null }
            val largest = ok.mapNotNull { r ->
                if (r.partX != null && r.partY != null && r.partZ != null) Dimensions(r.partX, r.partY, r.partZ) else null
            }.maxByOrNull { maxOf(it.xMm, it.yMm, it.zMm) }
            return ModelFacts(
                fit = GeometryAnalyzer.aggregateFit(ok.map { it.fitsU1 }),
                u1Profile = GeometryAnalyzer.aggregateU1(ok.map { it.u1Profile }),
                largestPart = largest,
                printerModel = ok.firstNotNullOfOrNull { it.printerModel },
            )
        }
    }
}
