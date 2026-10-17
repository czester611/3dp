package pl.u1companion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        HistoryEntity::class,
        SavedSearchEntity::class,
        LibraryFileEntity::class,
        PrinterEntity::class,
        PrintJobEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun savedSearches(): SavedSearchDao
    abstract fun libraryFiles(): LibraryFileDao
    abstract fun printers(): PrinterDao
    abstract fun printJobs(): PrintJobDao
}
