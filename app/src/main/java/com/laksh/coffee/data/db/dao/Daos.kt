package com.laksh.coffee.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BeanDao {
    @Query("SELECT * FROM beans WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<BeanEntity>>

    @Query("SELECT * FROM beans ORDER BY isArchived ASC, createdAt DESC")
    fun observeAll(): Flow<List<BeanEntity>>

    @Query("SELECT * FROM beans WHERE id = :id")
    suspend fun byId(id: Long): BeanEntity?

    @Query("SELECT * FROM beans ORDER BY id ASC")
    suspend fun all(): List<BeanEntity>

    @Query("SELECT * FROM beans WHERE uid = :uid")
    suspend fun byUid(uid: String): BeanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bean: BeanEntity): Long

    @Update suspend fun update(bean: BeanEntity)
    @Delete suspend fun delete(bean: BeanEntity)

    /** Backup restore, "Replace everything" only. Photo rows of these beans go by cascade. */
    @Query("DELETE FROM beans")
    suspend fun deleteAll()
}

@Dao
interface GrinderDao {
    @Query("SELECT * FROM grinders WHERE isArchived = 0 ORDER BY isDefault DESC, brand ASC")
    fun observeActive(): Flow<List<GrinderEntity>>

    @Query("SELECT * FROM grinders WHERE id = :id")
    suspend fun byId(id: Long): GrinderEntity?

    /** Archived ones too. */
    @Query("SELECT * FROM grinders ORDER BY id ASC")
    suspend fun all(): List<GrinderEntity>

    @Query("SELECT * FROM grinders WHERE uid = :uid")
    suspend fun byUid(uid: String): GrinderEntity?

    @Query("UPDATE grinders SET isDefault = 0")
    suspend fun clearDefaults()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(grinder: GrinderEntity): Long

    @Update suspend fun update(grinder: GrinderEntity)
    @Delete suspend fun delete(grinder: GrinderEntity)

    /** Backup restore, "Replace everything" only. */
    @Query("DELETE FROM grinders")
    suspend fun deleteAll()
}

@Dao
interface BrewLogDao {
    @Query("SELECT * FROM brew_logs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<BrewLogEntity>>

    @Query("SELECT * FROM brew_logs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BrewLogEntity>>

    @Query("SELECT * FROM brew_logs WHERE startedAt >= :sinceEpochMs ORDER BY startedAt DESC")
    fun observeSince(sinceEpochMs: Long): Flow<List<BrewLogEntity>>

    @Query("SELECT * FROM brew_logs WHERE beanId = :beanId ORDER BY startedAt DESC")
    fun observeForBean(beanId: Long): Flow<List<BrewLogEntity>>

    @Query("SELECT * FROM brew_logs WHERE id = :id")
    fun observeById(id: Long): Flow<BrewLogEntity?>

    @Query("SELECT * FROM brew_logs WHERE id = :id")
    suspend fun byId(id: Long): BrewLogEntity?

    @Query("SELECT COUNT(*) FROM brew_logs")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM brew_logs")
    suspend fun countAll(): Int

    @Query("SELECT * FROM brew_logs ORDER BY startedAt ASC")
    suspend fun all(): List<BrewLogEntity>

    @Query("SELECT * FROM brew_logs WHERE uid = :uid")
    suspend fun byUid(uid: String): BrewLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: BrewLogEntity): Long

    @Update suspend fun update(log: BrewLogEntity)
    @Delete suspend fun delete(log: BrewLogEntity)

    /** Backup restore, "Replace everything" only. Analyses and photo rows of these brews go by cascade. */
    @Query("DELETE FROM brew_logs")
    suspend fun deleteAll()
}

@Dao
interface SavedRecipeDao {
    @Query("SELECT * FROM saved_recipes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedRecipeEntity>>

    @Query("SELECT * FROM saved_recipes WHERE id = :id")
    suspend fun byId(id: String): SavedRecipeEntity?

    @Query("SELECT * FROM saved_recipes ORDER BY id ASC")
    suspend fun all(): List<SavedRecipeEntity>

    @Upsert suspend fun upsert(recipe: SavedRecipeEntity)

    @Query("DELETE FROM saved_recipes WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Backup restore, "Replace everything" only. */
    @Query("DELETE FROM saved_recipes")
    suspend fun deleteAll()
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE brewLogId = :brewLogId ORDER BY takenAt ASC")
    fun observeForBrew(brewLogId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE beanId = :beanId ORDER BY takenAt ASC")
    fun observeForBean(beanId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE brewLogId = :brewLogId ORDER BY takenAt ASC")
    suspend fun forBrew(brewLogId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE beanId = :beanId ORDER BY takenAt ASC")
    suspend fun forBean(beanId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE uid = :uid")
    suspend fun byUid(uid: String): PhotoEntity?

    @Query("SELECT * FROM photos ORDER BY id ASC")
    suspend fun all(): List<PhotoEntity>

    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Update suspend fun update(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Backup restore, "Replace everything" only. The caller removes the files afterwards. */
    @Query("DELETE FROM photos")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM photos WHERE brewLogId = :brewLogId")
    suspend fun countForBrew(brewLogId: Long): Int
}
