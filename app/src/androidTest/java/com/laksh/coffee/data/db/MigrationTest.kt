package com.laksh.coffee.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Room's own validation of MIGRATION_3_4 against schemas/…/4.json, on a device. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CoffeeDatabase::class.java)

    private fun SupportSQLiteDatabase.seedV3() {
        execSQL("INSERT INTO beans (id, name, roaster, origin, region, varietal, process, roastLevel, price, altitudeMasl, tastingNotes, notes, isArchived, createdAt) VALUES (1, 'Kiamabara', 'Square Mile', 'Kenya', '', '', 'Washed', 'MediumLight', '', '', '', '', 0, 1000)")
        execSQL("INSERT INTO beans (id, name, roaster, origin, region, varietal, process, roastLevel, price, altitudeMasl, tastingNotes, notes, isArchived, createdAt) VALUES (2, 'Mystery', '', '', '', '', 'Other', 'Unknown', '', '', '', '', 0, 2000)")
        execSQL("INSERT INTO grinders (id, brand, model, burrType, burrSizeMm, settingStyle, clicksPerRotation, micronsPerClick, lowerIsFiner, minSetting, maxSetting, step, notes, isDefault, isArchived) VALUES (1, '1Zpresso', 'K-Ultra', 'Conical', '48', 'Clicks', 10, NULL, 1, 0, 40, 1, '', 1, 0)")
        listOf("Hario V60" to "75", "Cold Brew" to "30", "V60" to "80", "Something else" to "NULL").forEachIndexed { i, (brewer, clicks) ->
            execSQL(
                "INSERT INTO brew_logs (id, startedAt, recipeId, recipeName, recipeAuthor, brewer, beanId, beanLabel, grinderId, grinderLabel, grindSetting, grindClicks, doseG, waterG, tempC, totalTimeSec, flowFeel, bedShape, sludge, channeling, bedNotes, overall, descriptors, faults, notes, nextTime, isFavourite, taste_acidity, taste_sweetness, taste_bitterness, taste_body, taste_balance, taste_aftertaste, taste_clarity) " +
                    "VALUES (${i + 1}, ${5000 + i}, 'hoffmann-v60', 'V60', 'James Hoffmann', '$brewer', 1, 'Kiamabara', 1, '1Zpresso K-Ultra', '75 clicks (7 turns + 5)', $clicks, 30, 500, 100, 210, 'Unset', 'Unset', 'Unset', 'Unset', '', 7, '', '', '', '', 0, 5, 5, 5, 5, 5, 5, 5)",
            )
        }
        execSQL("INSERT INTO saved_recipes (id, json, updatedAt) VALUES ('custom-1', '{}', 1)")
        execSQL("INSERT INTO analyses (id, kind, brewLogId, createdAt, promptVersion, inputHash, brewCount, newestBrewAt, model, host, rawText, resultJson, flagsJson, latencyMs) VALUES (1, 'brew', 1, 9000, 1, 'abc', 1, 5000, 'm', 'api.commandcode.ai', 'raw', NULL, '[]', 10)")
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String?> = query(sql).use { c ->
        buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getString(0)) }
    }

    @Test
    fun migrate3To4_keepsEveryRowAndMapsLegacyValues() {
        helper.createDatabase(dbName, 3).apply { seedV3(); close() }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        assertEquals(listOf("washed", "unknown"), db.strings("SELECT process FROM beans ORDER BY id"))
        assertEquals("medium-light", db.strings("SELECT roastLevel FROM beans WHERE id = 1").single())
        assertEquals("1000", db.strings("SELECT updatedAt FROM beans WHERE id = 1").single())
        assertEquals(listOf("hario-v60-02", "cold-brew-jar", "hario-v60-02", null), db.strings("SELECT brewerId FROM brew_logs ORDER BY id"))
        assertEquals("75", db.strings("SELECT grindClicks FROM brew_logs WHERE id = 1").single())
        assertEquals("10", db.strings("SELECT clicksPerRotation FROM grinders WHERE id = 1").single())
        assertEquals("commandcode", db.strings("SELECT providerId FROM analyses").single())
        assertEquals("android", db.strings("SELECT origin FROM analyses").single())
        assertEquals("0", db.strings("SELECT COUNT(*) FROM photos").single())
        val uids = db.strings("SELECT uid FROM beans UNION ALL SELECT uid FROM grinders UNION ALL SELECT uid FROM brew_logs UNION ALL SELECT uid FROM analyses")
        assertEquals(8, uids.size)
        uids.forEach { assertNotEquals("", it) }
        assertEquals(uids.size, uids.toSet().size)
        assertTrue(db.strings("PRAGMA foreign_key_check").isEmpty())
        assertEquals("ok", db.strings("PRAGMA integrity_check").single())
        db.close()
    }

    @Test
    fun openWithAllMigrations_fromVersion3() {
        helper.createDatabase(dbName, 3).apply { seedV3(); close() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, CoffeeDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            runBlocking {
                assertEquals(2, room.beanDao().all().size)
                assertEquals("washed", room.beanDao().byId(1)!!.processId)
                assertEquals(1, room.grinderDao().all().size)
                assertEquals(4, room.brewLogDao().countAll())
                assertEquals("hario-v60-02", room.brewLogDao().byId(1)!!.brewerId)
                assertNull(room.brewLogDao().byId(4)!!.brewerId)
                assertEquals(1, room.savedRecipeDao().all().size)
                assertEquals(1, room.analysisDao().all().size)
                assertTrue(room.photoDao().all().isEmpty())
            }
        } finally {
            room.close()
        }
    }
}
