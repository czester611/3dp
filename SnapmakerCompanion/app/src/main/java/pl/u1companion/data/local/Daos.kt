package pl.u1companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT key FROM favorites")
    fun observeKeys(): Flow<List<String>>

    @Upsert suspend fun upsert(e: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE key = :key")
    suspend fun delete(key: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<HistoryEntity>>

    @Insert suspend fun insert(e: HistoryEntity)

    @Query("DELETE FROM history WHERE at < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_searches ORDER BY COALESCE(lastRunAt, createdAt) DESC")
    fun observeAll(): Flow<List<SavedSearchEntity>>

    @Insert suspend fun insert(e: SavedSearchEntity): Long

    @Query("UPDATE saved_searches SET lastRunAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LibraryFileDao {
    @Query("SELECT * FROM library_files ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE id = :id")
    suspend fun get(id: Long): LibraryFileEntity?

    @Insert suspend fun insert(e: LibraryFileEntity): Long

    @Upsert suspend fun upsert(e: LibraryFileEntity)

    @Query("UPDATE library_files SET status = :status, error = :error, localPath = COALESCE(:path, localPath), sizeBytes = COALESCE(:size, sizeBytes) WHERE id = :id")
    suspend fun updateStatus(id: Long, status: LocalFileStatus, error: String? = null, path: String? = null, size: Long? = null)

    @Query("DELETE FROM library_files WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PrinterDao {
    @Query("SELECT * FROM printers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE id = :id")
    suspend fun get(id: String): PrinterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PrinterEntity)

    @Query("DELETE FROM printers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PrintJobDao {
    @Query("SELECT * FROM print_jobs ORDER BY startedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<PrintJobEntity>>

    @Insert suspend fun insert(e: PrintJobEntity): Long

    @Query("UPDATE print_jobs SET state = :state, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateState(id: Long, state: String, finishedAt: Long?)
}
