package pl.u1companion.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.u1companion.core.model.FileKind
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.local.AppDatabase
import pl.u1companion.data.local.LibraryFileEntity
import pl.u1companion.data.local.LocalFileStatus
import pl.u1companion.data.source.SourceException
import pl.u1companion.data.source.SourceRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Pobiera plik modelu do prywatnego katalogu aplikacji (files/library). */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val registry: SourceRegistry,
    private val http: OkHttpClient,
    private val db: AppDatabase,
    private val json: Json,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val libId = inputData.getLong(KEY_LIB_ID, -1)
        val source = SourceId.valueOf(inputData.getString(KEY_SOURCE)!!)
        val modelId = inputData.getString(KEY_MODEL_ID)!!
        val file = json.decodeFromString(ModelFile.serializer(), inputData.getString(KEY_FILE)!!)
        db.libraryFiles().updateStatus(libId, LocalFileStatus.DOWNLOADING)
        try {
            val ticket = registry[source].resolveDownload(modelId, file)
            val dir = File(applicationContext.filesDir, "library/${source.name.lowercase()}/$modelId").apply { mkdirs() }
            val safeName = ticket.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val target = File(dir, safeName)
            val req = Request.Builder().url(ticket.url).apply { ticket.headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Pusta odpowiedź")
                val tmp = File(dir, "$safeName.part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (!tmp.renameTo(target)) throw IOException("Nie można zapisać pliku")
            }
            db.libraryFiles().updateStatus(libId, LocalFileStatus.READY, path = target.absolutePath, size = target.length())
            Result.success()
        } catch (e: SourceException.Unauthorized) {
            db.libraryFiles().updateStatus(libId, LocalFileStatus.FAILED, error = e.message)
            Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else {
                db.libraryFiles().updateStatus(libId, LocalFileStatus.FAILED, error = e.message)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_LIB_ID = "lib_id"
        const val KEY_SOURCE = "source"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_FILE = "file"
    }
}

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val json: Json,
) {
    val files = db.libraryFiles().observeAll()

    suspend fun enqueue(model: ModelSummary, file: ModelFile): Long {
        val libId = db.libraryFiles().insert(
            LibraryFileEntity(
                modelKey = model.key, modelTitle = model.title, fileName = file.name, localPath = null,
                kind = file.kind.name, sizeBytes = file.sizeBytes, status = LocalFileStatus.QUEUED,
                sourceUrl = model.webUrl, licenseFamily = model.license?.family?.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_LIB_ID to libId,
                    DownloadWorker.KEY_SOURCE to model.source.name,
                    DownloadWorker.KEY_MODEL_ID to model.id,
                    DownloadWorker.KEY_FILE to json.encodeToString(ModelFile.serializer(), file),
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dl-$libId", ExistingWorkPolicy.KEEP, req)
        return libId
    }

    /** Import pliku spoza serwisów (np. z "Udostępnij" lub WebView MakerWorld). */
    suspend fun registerLocal(fileName: String, path: String, size: Long, sourceUrl: String?): Long =
        db.libraryFiles().insert(
            LibraryFileEntity(
                modelKey = null, modelTitle = fileName, fileName = fileName, localPath = path,
                kind = FileKind.fromFileName(fileName).name, sizeBytes = size, status = LocalFileStatus.READY,
                sourceUrl = sourceUrl, createdAt = System.currentTimeMillis(),
            ),
        )
}
