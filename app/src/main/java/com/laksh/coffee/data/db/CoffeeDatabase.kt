package com.laksh.coffee.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.laksh.coffee.data.ai.AnalysisDao
import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.db.dao.BeanDao
import com.laksh.coffee.data.db.dao.BrewLogDao
import com.laksh.coffee.data.db.dao.GrinderDao
import com.laksh.coffee.data.db.dao.PhotoDao
import com.laksh.coffee.data.db.dao.SavedRecipeDao
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity

@Database(
    entities = [
        BeanEntity::class,
        GrinderEntity::class,
        BrewLogEntity::class,
        SavedRecipeEntity::class,
        AnalysisEntity::class,
        PhotoEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CoffeeDatabase : RoomDatabase() {

    abstract fun beanDao(): BeanDao
    abstract fun grinderDao(): GrinderDao
    abstract fun brewLogDao(): BrewLogDao
    abstract fun savedRecipeDao(): SavedRecipeDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile private var instance: CoffeeDatabase? = null

        // Never fallbackToDestructiveMigration: a mismatch must not wipe someone's brews.
        fun get(context: Context): CoffeeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CoffeeDatabase::class.java,
                "coffee.db",
            ).addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
