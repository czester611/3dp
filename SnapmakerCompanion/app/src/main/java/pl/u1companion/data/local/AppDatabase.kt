package pl.u1companion.data.local

import androidx.room.AutoMigration
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
        ModelGeometryEntity::class,
        CategoryCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // v2: model_geometry + category_cache (nowe tabele -> automatyczna migracja ze schematu schemas/1.json)
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun savedSearches(): SavedSearchDao
    abstract fun libraryFiles(): LibraryFileDao
    abstract fun printers(): PrinterDao
    abstract fun printJobs(): PrintJobDao
    abstract fun geometry(): GeometryDao
    abstract fun categoryCache(): CategoryCacheDao
}
