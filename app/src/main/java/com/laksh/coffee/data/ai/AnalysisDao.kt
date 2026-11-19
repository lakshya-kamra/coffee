package com.laksh.coffee.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {

    @Query("SELECT * FROM analyses WHERE kind = 'brew' AND brewLogId = :brewLogId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestForBrew(brewLogId: Long): Flow<AnalysisEntity?>

    @Query("SELECT * FROM analyses WHERE kind = 'trend' ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestTrend(): Flow<AnalysisEntity?>

    @Query("SELECT DISTINCT brewLogId FROM analyses WHERE kind = 'brew' AND brewLogId IS NOT NULL")
    fun observeAnalysedBrewIds(): Flow<List<Long>>

    @Insert
    suspend fun insert(row: AnalysisEntity): Long

    /** Every stored analysis, for backup. */
    @Query("SELECT * FROM analyses ORDER BY createdAt ASC")
    suspend fun all(): List<AnalysisEntity>

    @Query("SELECT * FROM analyses WHERE uid = :uid")
    suspend fun byUid(uid: String): AnalysisEntity?

    /** Keeps the newest [keep] analyses of one brew. */
    @Query(
        "DELETE FROM analyses WHERE kind = 'brew' AND brewLogId = :brewLogId AND id NOT IN " +
            "(SELECT id FROM analyses WHERE kind = 'brew' AND brewLogId = :brewLogId ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun pruneBrew(brewLogId: Long, keep: Int)

    /** Keeps the newest [keep] whole-log analyses. */
    @Query(
        "DELETE FROM analyses WHERE kind = 'trend' AND id NOT IN " +
            "(SELECT id FROM analyses WHERE kind = 'trend' ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun pruneTrend(keep: Int)

    @Query("DELETE FROM analyses WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Backup restore, "Replace everything" only. */
    @Query("DELETE FROM analyses")
    suspend fun deleteAll()

    /** Debug seed only: the rows written by one host (the sample host). */
    @Query("SELECT * FROM analyses WHERE host = :host")
    suspend fun byHost(host: String): List<AnalysisEntity>

    /** Debug seed only: re-stamps a sample with the current prompt's hash. */
    @Query("UPDATE analyses SET inputHash = :hash WHERE id = :id")
    suspend fun setInputHash(id: Long, hash: String)
}
