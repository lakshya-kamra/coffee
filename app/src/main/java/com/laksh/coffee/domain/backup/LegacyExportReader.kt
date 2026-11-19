package com.laksh.coffee.domain.backup

import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.catalog.LegacyIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Reads the 1.x JSON export ("schema": "coffee-brew-log/1", written by "Save JSON export" in
 * every build up to 1.0, database versions 1 to 3) into a v2 document.
 *
 * It has no uids, so each record gets uid = "legacy-" + the first 16 hex characters of
 * SHA-256("<exportedAt>|<type>|<id>") over UTF-8, with exportedAt exactly as written in the file
 * and type one of "bean", "grinder", "brew". Importing the same file twice therefore changes
 * nothing, on the phone or on the web. Records carry no updatedAt, so they never replace a row
 * that is already here.
 */
object LegacyExportReader {

    fun uid(exportedAtAsWritten: String, type: String, id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$exportedAtAsWritten|$type|$id".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder("legacy-")
        for (i in 0 until 8) hex.append(HEX[(digest[i].toInt() shr 4) and 0xF]).append(HEX[digest[i].toInt() and 0xF])
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** The 1.x id as written (a number), kept as text so a hand-edited "7" hashes like 7. */
    private fun idText(v: JsonElement?): String? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString) return p.content.trim().takeIf { it.isNotEmpty() }
        return Lenient.longOrNull(v)?.toString()
    }

    /** GrinderEntity.display: brand and model, blanks dropped, joined by a space. */
    private fun grinderLabel(brand: String, model: String): String =
        listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ")

    private fun array(v: JsonElement?): List<JsonElement> = (v as? JsonArray).orEmpty()

    /** [builtIns] is what a 1.x brew's recipe name + author is looked up in, in catalog order. */
    fun read(root: JsonObject, builtIns: List<Recipe>): Decoded {
        val problems = mutableListOf<String>()
        val exportedAtRaw = Lenient.str(root["exportedAt"])
        val exportedAt = BackupTime.parse(exportedAtRaw)?.let(BackupTime::format).orEmpty()

        val beanUids = HashMap<String, String>()
        val beans = ArrayList<BeanDto>()
        array(root["beans"]).forEachIndexed { i, e ->
            val b = e as? JsonObject
            val id = b?.let { idText(it["id"]) }
            if (b == null || id == null) {
                problems += "beans[$i]: no id"
                return@forEachIndexed
            }
            if (Lenient.str(b["name"]).isBlank()) {
                problems += "beans[$i]: no name"
                return@forEachIndexed
            }
            val uid = uid(exportedAtRaw, "bean", id)
            beanUids[id] = uid
            val process = Lenient.str(b["process"]).trim()
            val roast = Lenient.str(b["roastLevel"]).trim()
            val roastedDay = Lenient.longOrNull(b["roastedOnEpochDay"])
            beans += BeanDto(
                uid = uid,
                name = Lenient.str(b["name"]),
                roaster = Lenient.str(b["roaster"]),
                origin = Lenient.str(b["origin"]),
                region = Lenient.str(b["region"]),
                varietal = Lenient.str(b["varietal"]),
                processId = LegacyIds.PROCESS_LABEL[process] ?: LegacyIds.PROCESS[process] ?: "",
                roastLevelId = LegacyIds.ROAST_LEVEL_LABEL[roast] ?: LegacyIds.ROAST_LEVEL[roast] ?: "",
                roastedOn = roastedDay?.let { day -> runCatching { BackupTime.date(day) }.getOrNull() }
                    ?: BackupTime.dateOrNull(Lenient.str(b["roastedOn"])),
                tastingNotes = Lenient.str(b["tastingNotes"]),
            )
        }

        val grinders = ArrayList<GrinderDto>()
        val grinderByLabel = HashMap<String, String>()
        array(root["grinders"]).forEachIndexed { i, e ->
            val g = e as? JsonObject
            val id = g?.let { idText(it["id"]) }
            if (g == null || id == null) {
                problems += "grinders[$i]: no id"
                return@forEachIndexed
            }
            val uid = uid(exportedAtRaw, "grinder", id)
            val brand = Lenient.str(g["brand"])
            val model = Lenient.str(g["model"])
            val label = grinderLabel(brand, model)
            // Two grinders with the same name: brews link to the first, as a label can say no more.
            if (label.isNotEmpty() && label !in grinderByLabel) grinderByLabel[label] = uid
            grinders += GrinderDto(
                uid = uid,
                brand = brand,
                model = model,
                burr = BackupIds.burrId(Lenient.str(g["burrType"])),
                // Fields 1.x did not export take Room's defaults (0 to 40, step 1). The click
                // count per turn is kept exactly as stored: a 1.x K-Ultra row stays at 10.
                setting = GrinderSettingDto(
                    style = BackupIds.styleId(Lenient.str(g["settingStyle"])),
                    min = 0.0,
                    max = 40.0,
                    step = 1.0,
                    lowerIsFiner = Lenient.bool(g["lowerIsFiner"], true),
                    clicksPerRotation = Lenient.intOrNull(g["clicksPerRotation"])?.takeIf { it > 0 },
                    micronsPerStep = Lenient.numOrNull(g["micronsPerClick"]),
                    dialFormat = "",
                ),
            )
        }

        val brews = ArrayList<BrewDto>()
        array(root["brews"]).forEachIndexed { i, e ->
            val l = e as? JsonObject
            val id = l?.let { idText(it["id"]) }
            if (l == null || id == null) {
                problems += "brews[$i]: no id"
                return@forEachIndexed
            }
            val startedAt = BackupTime.canonical(l["startedAt"])
            if (startedAt == null) {
                problems += "brews[$i]: no startedAt"
                return@forEachIndexed
            }
            val recipeName = Lenient.str(l["recipe"])
            val recipeAuthor = Lenient.str(l["recipeAuthor"])
            val brewerLabel = Lenient.str(l["brewer"])
            val grinder = Lenient.str(l["grinder"])
            val t = Lenient.obj(l["taste"]) ?: JsonObject(emptyMap())
            brews += BrewDto(
                uid = uid(exportedAtRaw, "brew", id),
                startedAt = startedAt,
                recipeId = builtIns.firstOrNull { it.name == recipeName && it.author == recipeAuthor }?.id.orEmpty(),
                recipeName = recipeName,
                recipeAuthor = recipeAuthor,
                brewerId = LegacyIds.BREWER[brewerLabel.trim()],
                brewerLabel = brewerLabel,
                beanUid = idText(l["beanId"])?.let { beanUids[it] },
                beanLabel = Lenient.str(l["bean"]),
                daysOffRoast = Lenient.intOrNull(l["daysOffRoast"]),
                grinderUid = grinder.trim().takeIf { it.isNotEmpty() }?.let { grinderByLabel[it] },
                grinderLabel = grinder,
                grindSetting = Lenient.str(l["grindSetting"]),
                grindClicks = Lenient.intOrNull(l["grindClicks"]),
                doseG = Lenient.num(l["doseG"]),
                waterG = Lenient.num(l["waterG"]),
                tempC = Lenient.int(l["tempC"]),
                totalTimeSec = Lenient.int(l["totalTimeSec"]),
                drawdownSec = Lenient.intOrNull(l["drawdownSec"]),
                beverageG = Lenient.numOrNull(l["beverageG"]),
                tdsPercent = Lenient.numOrNull(l["tdsPercent"]),
                extractionYield = Lenient.numOrNull(l["extractionYield"]),
                flow = BackupIds.flowId(Lenient.str(l["flowFeel"])),
                bed = BedDto(
                    shape = BackupIds.bedId(Lenient.str(l["bedShape"])),
                    surface = BackupIds.sludgeId(Lenient.str(l["sludge"])),
                    channelling = BackupIds.channellingId(Lenient.str(l["channeling"])),
                    notes = Lenient.str(l["bedNotes"]),
                ),
                taste = TasteDto(
                    acidity = Lenient.int(t["acidity"], 5),
                    sweetness = Lenient.int(t["sweetness"], 5),
                    bitterness = Lenient.int(t["bitterness"], 5),
                    body = Lenient.int(t["body"], 5),
                    balance = Lenient.int(t["balance"], 5),
                    aftertaste = Lenient.int(t["aftertaste"], 5),
                    clarity = Lenient.int(t["clarity"], 5),
                ),
                overall = Lenient.int(l["overall"]),
                descriptors = Lenient.strList(l["descriptors"]),
                faults = Lenient.strList(l["faults"]),
                notes = Lenient.str(l["notes"]),
                nextTime = Lenient.str(l["nextTime"]),
            )
        }

        val doc = BackupDoc(
            exportedAt = exportedAt,
            app = BackupApp(platform = "android", appVersion = "1.x", packageName = "", dbVersion = 0),
            options = BackupOptions(includesPhotos = false, includesAnalyses = false),
            beans = beans,
            grinders = grinders,
            brews = brews,
        )
        return Decoded(doc, problems)
    }
}
