package com.laksh.coffee.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs MIGRATION_3_4's exact SQL on a real SQLite (JVM, no emulator) and checks the result has
 * the same schema as a fresh v4 database built from the exported schemas, the way Room's
 * MigrationTestHelper validates it, plus every value mapping. The instrumented MigrationTest
 * repeats this on a device through Room itself.
 */
class MigrationSqlTest {

    private val schemas = listOf(File("schemas"), File("app/schemas")).first { it.isDirectory }
        .resolve("com.laksh.coffee.data.db.CoffeeDatabase")

    private val open = mutableListOf<Connection>()

    @After fun close() = open.forEach { it.close() }

    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { open += it }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<Any?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }
    }

    private fun Connection.one(sql: String): Any? = rows(sql).single().single()

    /** Creates every table and index of an exported schema version. */
    private fun Connection.createFromSchema(version: Int) {
        val root = Json.parseToJsonElement(schemas.resolve("$version.json").readText()) as JsonObject
        val entities = root.getValue("database").jsonObject.getValue("entities").jsonArray
        entities.forEach { e ->
            val o = e.jsonObject
            val table = o.getValue("tableName").jsonPrimitive.content
            exec(o.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            (o["indices"] as? JsonArray)?.forEach { i ->
                exec(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
    }

    private fun Connection.tables(): List<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it[0] as String }

    /** What Room compares: columns (name, type, not null, default, pk), foreign keys and indices. */
    private fun Connection.shape(table: String): Triple<Set<List<Any?>>, Set<List<Any?>>, Set<List<Any?>>> {
        val columns = rows("PRAGMA table_info(`$table`)").map { it.drop(1) }.toSet()
        val fks = rows("PRAGMA foreign_key_list(`$table`)").map { it.drop(2) }.toSet()
        val indices = rows("PRAGMA index_list(`$table`)")
            .filter { !(it[1] as String).startsWith("sqlite_autoindex") }
            .map { idx ->
                val name = idx[1] as String
                listOf(name, idx[2], rows("PRAGMA index_info(`$name`)").map { it[2] })
            }.toSet()
        return Triple(columns, fks, indices)
    }

    private fun seededV3(): Connection = db().apply {
        createFromSchema(3)
        exec("INSERT INTO beans (id, name, roaster, origin, region, varietal, process, roastLevel, price, altitudeMasl, tastingNotes, notes, isArchived, createdAt) VALUES (1, 'Kiamabara', 'Square Mile', 'Kenya', '', '', 'Washed', 'MediumLight', '', '', '', '', 0, 1000)")
        exec("INSERT INTO beans (id, name, roaster, origin, region, varietal, process, roastLevel, price, altitudeMasl, tastingNotes, notes, isArchived, createdAt) VALUES (2, 'Mystery', '', '', '', '', 'Other', 'Unknown', '', '', '', '', 0, 2000)")
        exec("INSERT INTO grinders (id, brand, model, burrType, burrSizeMm, settingStyle, clicksPerRotation, micronsPerClick, lowerIsFiner, minSetting, maxSetting, step, notes, isDefault, isArchived) VALUES (1, '1Zpresso', 'K-Ultra', 'Conical', '48', 'Clicks', 10, NULL, 1, 0, 40, 1, '', 1, 0)")
        listOf("Hario V60" to 75, "Cold Brew" to 30, "V60" to 80, "Something else" to null).forEachIndexed { i, (brewer, clicks) ->
            exec(
                "INSERT INTO brew_logs (id, startedAt, recipeId, recipeName, recipeAuthor, brewer, beanId, beanLabel, grinderId, grinderLabel, grindSetting, grindClicks, doseG, waterG, tempC, totalTimeSec, flowFeel, bedShape, sludge, channeling, bedNotes, overall, descriptors, faults, notes, nextTime, isFavourite, taste_acidity, taste_sweetness, taste_bitterness, taste_body, taste_balance, taste_aftertaste, taste_clarity) " +
                    "VALUES (${i + 1}, ${5000 + i}, 'hoffmann-v60', 'V60', 'James Hoffmann', '$brewer', 1, 'Kiamabara', 1, '1Zpresso K-Ultra', '75 clicks (7 turns + 5)', ${clicks ?: "NULL"}, 30, 500, 100, 210, 'Unset', 'Unset', 'Unset', 'Unset', '', 7, '', '', '', '', 0, 5, 5, 5, 5, 5, 5, 5)",
            )
        }
        exec("INSERT INTO saved_recipes (id, json, updatedAt) VALUES ('custom-1', '{}', 1)")
        exec("INSERT INTO analyses (id, kind, brewLogId, createdAt, promptVersion, inputHash, brewCount, newestBrewAt, model, host, rawText, resultJson, flagsJson, latencyMs) VALUES (1, 'brew', 1, 9000, 1, 'abc', 1, 5000, 'm', 'api.commandcode.ai', 'raw', NULL, '[]', 10)")
        exec("INSERT INTO analyses (id, kind, brewLogId, createdAt, promptVersion, inputHash, brewCount, newestBrewAt, model, host, rawText, resultJson, flagsJson, latencyMs) VALUES (2, 'trend', NULL, 9001, 1, 'def', 4, 5003, 'm', 'example.com', 'raw', NULL, '[]', 10)")
    }

    @Test
    fun migratedDatabaseHasExactlyTheV4Schema() {
        val migrated = seededV3()
        MIGRATION_3_4_SQL.forEach { migrated.exec(it) }
        val fresh = db().apply { createFromSchema(4) }

        assertEquals(fresh.tables(), migrated.tables())
        fresh.tables().forEach { t ->
            val want = fresh.shape(t)
            val got = migrated.shape(t)
            assertEquals("$t columns", want.first, got.first)
            assertEquals("$t foreign keys", want.second, got.second)
            assertEquals("$t indices", want.third, got.third)
        }
    }

    @Test
    fun everyRowIsKeptAndLegacyValuesAreMapped() {
        val d = seededV3()
        MIGRATION_3_4_SQL.forEach { d.exec(it) }

        assertEquals(listOf(listOf("washed", "medium-light", 1000), listOf("unknown", "", 2000)),
            d.rows("SELECT process, roastLevel, updatedAt FROM beans ORDER BY id"))
        assertEquals(listOf("hario-v60-02", "cold-brew-jar", "hario-v60-02", null),
            d.rows("SELECT brewerId FROM brew_logs ORDER BY id").map { it[0] })
        assertEquals(75, d.one("SELECT grindClicks FROM brew_logs WHERE id = 1"))
        assertEquals("75 clicks (7 turns + 5)", d.one("SELECT grindSetting FROM brew_logs WHERE id = 1"))
        assertEquals(5000, d.one("SELECT updatedAt FROM brew_logs WHERE id = 1"))
        assertEquals(10, d.one("SELECT clicksPerRotation FROM grinders WHERE id = 1"))
        assertNull(d.one("SELECT catalogId FROM grinders WHERE id = 1"))
        assertEquals("", d.one("SELECT dialFormat FROM grinders WHERE id = 1"))
        assertEquals(listOf(listOf("commandcode", "android", 0), listOf("custom", "android", 0)),
            d.rows("SELECT providerId, origin, photoCount FROM analyses ORDER BY id"))
        assertEquals(1, d.one("SELECT COUNT(*) FROM saved_recipes"))
        assertEquals(0, d.one("SELECT COUNT(*) FROM photos"))

        val uids = listOf("beans", "grinders", "brew_logs", "analyses").flatMap { t ->
            d.rows("SELECT uid FROM $t").map { it[0] as String }
        }
        assertEquals(9, uids.size)
        uids.forEach { assertNotEquals("", it) }
        assertEquals(uids.size, uids.toSet().size)

        assertTrue(d.rows("PRAGMA foreign_key_check").isEmpty())
        assertEquals("ok", d.one("PRAGMA integrity_check"))
    }

    @Test
    fun theBrewerCaseCoversEveryLegacyName() {
        val sql = MIGRATION_3_4_SQL.single { it.startsWith("UPDATE `brew_logs` SET `brewerId`") }
        listOf("Hario V60", "V60", "Chemex", "Kalita Wave", "Kalita", "Hario Switch", "Switch", "AeroPress",
            "French Press", "FrenchPress", "Moka Pot", "Moka", "Espresso", "Cold Brew", "ColdBrew").forEach {
            assertTrue(it, sql.contains("WHEN '$it' THEN"))
        }
        assertTrue(sql.endsWith("ELSE NULL END"))
    }
}
