package com.laksh.coffee.recipes

import com.laksh.coffee.domain.Recipe
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved recipes written by 1.x must keep decoding, and re-save in the 2.0 shape. */
class RecipeSerializationTest {

    // The repository's own settings (CoffeeRepository.json).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val oneX = """
        {"id":"custom-1","name":"Mine","author":"You","brewer":"V60","doseG":15.0,"waterG":250.0,
         "grind":"Medium","tempC":96,"blurb":"","sourceNote":"","prep":[],"tips":[],
         "steps":[{"kind":"Bloom","title":"Bloom","detail":"","startSec":0,"endSec":45,"targetWaterG":50},
                  {"kind":"Pour","title":"Pour","detail":"","startSec":45,"endSec":120,"targetWaterG":250}],
         "accent":4293503061,"isBuiltIn":false}
    """.trimIndent()

    @Test
    fun aOneXSavedRecipeDecodes() {
        val r = json.decodeFromString(Recipe.serializer(), oneX)
        assertEquals("hario-v60-02", r.brewer.id)
        assertEquals(0xFFE9A855, r.accent)
        assertEquals("", r.fidelity)
        assertEquals(2, r.steps.size)
    }

    @Test
    fun reEncodingWritesIdsAndHex() {
        val r = json.decodeFromString(Recipe.serializer(), oneX)
        val out = json.encodeToString(Recipe.serializer(), r)
        assertTrue(out, out.contains("\"brewer\":\"hario-v60-02\""))
        assertTrue(out, out.contains("\"accent\":\"#E9A855\""))
        val back = json.decodeFromString(Recipe.serializer(), out)
        assertEquals(r, back)
    }

    @Test
    fun translucentAccentsKeepTheirAlpha() {
        val r = json.decodeFromString(Recipe.serializer(), oneX).copy(accent = 0x80112233)
        val out = json.encodeToString(Recipe.serializer(), r)
        assertTrue(out, out.contains("\"accent\":\"#80112233\""))
        assertEquals(0x80112233, json.decodeFromString(Recipe.serializer(), out).accent)
    }
}
