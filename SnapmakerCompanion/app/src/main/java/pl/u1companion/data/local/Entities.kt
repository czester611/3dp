package pl.u1companion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Ulubione - snapshot podsumowania, żeby lista działała offline. */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,          // "PRINTABLES:3161"
    val source: String,
    val modelId: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String?,
    val licenseFamily: String?,
    val webUrl: String,
    val addedAt: Long,
)

enum class HistoryType { VIEWED, DOWNLOADED, SENT_TO_PRINTER, PRINTED }

@Entity(tableName = "history", indices = [Index("modelKey"), Index("at")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: HistoryType,
    val modelKey: String?,
    val title: String,
    val thumbnailUrl: String?,
    val detail: String? = null,
    val at: Long,
)

@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** SearchQuery zserializowane do JSON (kotlinx.serialization). */
    val queryJson: String,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    /** Etap 4: powiadomienia o nowych wynikach (WorkManager periodic). */
    val notifyNew: Boolean = false,
)

enum class LocalFileStatus { QUEUED, DOWNLOADING, READY, FAILED, SLICING, SLICED }

/** Plik w lokalnej bibliotece (pobrany z serwisu, zaimportowany lub wynik slicingu). */
@Entity(tableName = "library_files", indices = [Index("modelKey")])
data class LibraryFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelKey: String?,
    val modelTitle: String?,
    val fileName: String,
    val localPath: String?,
    val kind: String,                     // FileKind.name
    val sizeBytes: Long?,
    val status: LocalFileStatus,
    val error: String? = null,
    val sourceUrl: String? = null,
    val licenseFamily: String? = null,
    /** Wyciągnięte z nagłówka G-code (GcodeInspector) - JSON. */
    val gcodeMetaJson: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey val id: String,           // numer seryjny z mDNS (TXT "sn") lub host:port
    val name: String,
    val host: String,
    val port: Int = 7125,
    /** Opcjonalny klucz API Moonraker (gdy włączone [authorization] i klient nie jest "trusted"). */
    val apiKey: String? = null,
    val model: String? = null,
    val firmwareVersion: String? = null,
    val lastSeenAt: Long,
    val manuallyAdded: Boolean = false,
)

@Entity(tableName = "print_jobs", indices = [Index("printerId")])
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val printerId: String,
    val remoteFileName: String,
    val libraryFileId: Long?,
    val modelKey: String?,
    val configJson: String?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val state: String,                    // standby/printing/paused/complete/cancelled/error
)
