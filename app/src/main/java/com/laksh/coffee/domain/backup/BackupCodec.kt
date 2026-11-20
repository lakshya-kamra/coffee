package com.laksh.coffee.domain.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** What a picked file turned out to be. */
enum class BackupKind { V2, Legacy1, Unknown, TooNew }

/** A decoded file plus the records that were left out, each with a reason ("brews[3]: no uid"). */
data class Decoded(val doc: BackupDoc, val problems: List<String>)

/** Field readers that never throw: a wrong type reads as the default, as the web codec does. */
internal object Lenient {

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun obj(v: JsonElement?): JsonObject? = v as? JsonObject

    fun str(v: JsonElement?, default: String = ""): String = v.prim()?.takeIf { it.isString }?.content ?: default

    /** A non-empty string, else null. */
    fun strOrNull(v: JsonElement?): String? = str(v).takeIf { it.isNotEmpty() }

    /** Text, or a bare number as it was written (altitude 1700, burr size 48). */
    fun strOrNumber(v: JsonElement?): String {
        val p = v.prim() ?: return ""
        return if (p.isString) p.content else numOrNull(v)?.let { n -> if (n == Math.floor(n) && Math.abs(n) < 1e15) n.toLong().toString() else n.toString() }.orEmpty()
    }

    /** A finite number, or a string holding one. NaN and Infinity (1.x could write them) are null. */
    fun numOrNull(v: JsonElement?): Double? {
        val p = v.prim() ?: return null
        val text = if (p.isString) p.content.trim() else p.content
        if (text.isEmpty() || !NUMBER.matches(text)) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun num(v: JsonElement?, default: Double = 0.0): Double = numOrNull(v) ?: default

    /** Truncated toward zero, as a file written by JavaScript may hold 95.0 for 95. */
    fun longOrNull(v: JsonElement?): Long? = numOrNull(v)?.let { n ->
        if (Math.abs(n) > 9.0e15) null else n.toLong()
    }

    fun intOrNull(v: JsonElement?): Int? = longOrNull(v)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    fun int(v: JsonElement?, default: Int = 0): Int = intOrNull(v) ?: default

    fun boolOrNull(v: JsonElement?): Boolean? {
        val p = v.prim() ?: return null
        if (p.isString) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun bool(v: JsonElement?, default: Boolean = false): Boolean = boolOrNull(v) ?: default

    fun strList(v: JsonElement?): List<String> =
        (v as? JsonArray)?.mapNotNull { e -> e.prim()?.takeIf { it.isString }?.content }.orEmpty()

    fun uid(v: JsonElement?): String? = str(v).takeIf { it.isNotEmpty() && it.length <= Backup.UID_MAX }

    fun oneOf(v: JsonElement?, allowed: Collection<String>): String? = str(v).takeIf { it in allowed }

    // Plain decimal notation only: toDoubleOrNull alone would also take "NaN", "1f" and hex floats.
    private val NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
}

/** Instants and dates as the file writes them. */
object BackupTime {

    private val INSTANT = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?([Zz]|[+-]\\d{2}:?\\d{2})?$",
    )
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /**
     * Epoch ms from an ISO-8601 instant, with or without a fraction (Java writes up to nine
     * digits: 1.x wrote "…07.123456Z"; anything past milliseconds is dropped). No zone means UTC.
     */
    fun parse(text: String?): Long? {
        val m = INSTANT.matchEntire(text?.trim().orEmpty()) ?: return null
        val g = m.groupValues
        val millis = g[7].padEnd(3, '0').take(3).toInt()
        val local = try {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        } catch (e: DateTimeException) {
            return null
        }
        var ms = local.toEpochSecond(ZoneOffset.UTC) * 1000 + millis
        val zone = g[8]
        if (zone.isNotEmpty() && !zone.equals("Z", ignoreCase = true)) {
            val sign = if (zone.startsWith("-")) -1 else 1
            val digits = zone.drop(1).replace(":", "")
            ms -= sign * (digits.take(2).toInt() * 60L + digits.drop(2).take(2).toInt()) * 60_000L
        }
        return ms
    }

    /** An instant string, or a bare number taken as epoch ms. */
    internal fun parse(v: JsonElement?): Long? {
        val p = (v as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        return if (p.isString) parse(p.content) else Lenient.longOrNull(v)
    }

    /** `Instant.toString()`: "2026-11-17T09:41:00Z", with milliseconds only when there are some. */
    fun format(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    /** The instant rewritten in this app's form, or null when unreadable. */
    internal fun canonical(v: JsonElement?): String? = parse(v)?.let(::format)

    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /** "YYYY-MM-DD" (anything after the tenth character is ignored), or null. */
    fun dateOrNull(text: String?): String? {
        val t = text?.trim().orEmpty().take(10)
        if (!DATE.matches(t)) return null
        return try {
            LocalDate.parse(t).toString()
        } catch (e: DateTimeException) {
            null
        }
    }

    fun epochDay(date: String?): Long? = dateOrNull(date)?.let { LocalDate.parse(it).toEpochDay() }
}

/**
 * Reads and writes backup files. Reading is lenient (every field has a default, unknown keys
 * are ignored, a record that cannot be used is left out with a reason); writing writes every
 * key, nulls included. Pure: it runs in JVM tests and off the main thread on the phone.
 */
object BackupCodec {

    // Lenient so the bare NaN / Infinity / -Infinity tokens 1.x could write are read (as
    // unquoted literals that Lenient.numOrNull then turns into null). Text inside quotes is
    // untouched, so a note that says "NaN" stays as typed.
    private val reader = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        isLenient = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val writer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = true
    }

    private const val BOM = 0xFEFF

    // Added in 2.1. An unknown value reads as "" (not recorded), never as an error.
    private val BEAN_FORMS = listOf("whole", "ground")
    private val GROUND_FOR = listOf("filter", "press", "espresso", "moka")
    private val SETTING_UNITS = listOf("s")
    private val GRIND_KINDS = listOf("dial", "blade", "preground")

    /** Compact, for JSON kept in a database column. */
    internal val compact = Json { encodeDefaults = true }

    // ------------------------------------------------------------------ parse / detect

    /** The file as a JSON value, or null when it is not JSON at all. */
    fun parse(text: String): JsonElement? {
        // A UTF-8 byte-order mark is legal in a file but not in JSON.
        val body = if (text.isNotEmpty() && text[0].code == BOM) text.substring(1) else text
        if (body.isBlank()) return null
        return try {
            reader.parseToJsonElement(body)
        } catch (e: Exception) {
            null
        }
    }

    fun detect(root: JsonElement?): BackupKind {
        val o = root as? JsonObject ?: return BackupKind.Unknown
        if (Lenient.str(o["format"]) == Backup.FORMAT) {
            val raw = Lenient.numOrNull(o["version"]) ?: return BackupKind.Unknown
            if (raw != Math.floor(raw)) return BackupKind.Unknown
            val version = raw.toLong()
            return when {
                version > Backup.VERSION -> BackupKind.TooNew
                version == Backup.VERSION.toLong() -> BackupKind.V2
                else -> BackupKind.Unknown
            }
        }
        val schema = Lenient.str(o["schema"])
        if (schema == Backup.LEGACY_SCHEMA) return BackupKind.Legacy1
        val later = Regex("^coffee-brew-log/(\\d+)$").matchEntire(schema)?.groupValues?.get(1)?.toLongOrNull()
        return if (later != null && later > 1) BackupKind.TooNew else BackupKind.Unknown
    }

    fun detect(text: String): BackupKind = detect(parse(text))

    // ------------------------------------------------------------------ decode v2

    fun decodeBean(o: JsonObject): BeanDto? {
        val uid = Lenient.uid(o["uid"]) ?: return null
        val name = Lenient.str(o["name"])
        if (name.isBlank()) return null
        return BeanDto(
            uid = uid,
            name = name,
            roaster = Lenient.str(o["roaster"]),
            roasterId = Lenient.strOrNull(o["roasterId"]),
            origin = Lenient.str(o["origin"]),
            originId = Lenient.strOrNull(o["originId"]),
            region = Lenient.str(o["region"]),
            farm = Lenient.str(o["farm"]),
            producer = Lenient.str(o["producer"]),
            washingStation = Lenient.str(o["washingStation"]),
            lotTypeId = Lenient.strOrNull(o["lotTypeId"]),
            speciesId = Lenient.strOrNull(o["speciesId"]),
            varietalIds = Lenient.strList(o["varietalIds"]),
            varietal = Lenient.str(o["varietal"]),
            processId = Lenient.str(o["processId"]),
            isDecaf = Lenient.bool(o["isDecaf"]),
            decafMethodId = Lenient.strOrNull(o["decafMethodId"]),
            roastLevelId = Lenient.str(o["roastLevelId"]),
            roastIntentId = Lenient.strOrNull(o["roastIntentId"]),
            form = Lenient.oneOf(o["form"], BEAN_FORMS) ?: "",
            groundFor = Lenient.oneOf(o["groundFor"], GROUND_FOR) ?: "",
            altitudeM = Lenient.strOrNumber(o["altitudeM"]),
            harvest = Lenient.str(o["harvest"]),
            roastedOn = BackupTime.dateOrNull(Lenient.str(o["roastedOn"])),
            openedOn = BackupTime.dateOrNull(Lenient.str(o["openedOn"])),
            restDays = Lenient.intOrNull(o["restDays"]),
            bagSizeG = Lenient.numOrNull(o["bagSizeG"]),
            priceAmount = Lenient.numOrNull(o["priceAmount"]),
            currency = Lenient.str(o["currency"]),
            priceText = Lenient.str(o["priceText"]),
            tastingNotes = Lenient.str(o["tastingNotes"]),
            notes = Lenient.str(o["notes"]),
            isArchived = Lenient.bool(o["isArchived"]),
            createdAt = BackupTime.canonical(o["createdAt"]),
            updatedAt = BackupTime.canonical(o["updatedAt"]),
        )
    }

    fun decodeGrinder(o: JsonObject): GrinderDto? {
        val uid = Lenient.uid(o["uid"]) ?: return null
        val s = Lenient.obj(o["setting"]) ?: JsonObject(emptyMap())
        val cpr = Lenient.intOrNull(s["clicksPerRotation"])
        return GrinderDto(
            uid = uid,
            catalogId = Lenient.strOrNull(o["catalogId"]),
            brand = Lenient.str(o["brand"]),
            model = Lenient.str(o["model"]),
            burr = BackupIds.burrId(Lenient.str(o["burr"])),
            burrSizeMm = Lenient.strOrNumber(o["burrSizeMm"]),
            setting = GrinderSettingDto(
                style = BackupIds.styleId(Lenient.str(s["style"])),
                min = Lenient.num(s["min"], 0.0),
                max = Lenient.num(s["max"], 40.0),
                step = Lenient.num(s["step"], 1.0),
                lowerIsFiner = Lenient.bool(s["lowerIsFiner"], true),
                clicksPerRotation = cpr?.takeIf { it > 0 },
                micronsPerStep = Lenient.numOrNull(s["micronsPerStep"]),
                dialFormat = Lenient.str(s["dialFormat"]),
                settingUnit = Lenient.oneOf(s["settingUnit"], SETTING_UNITS) ?: "",
            ),
            defaultFilterSetting = Lenient.numOrNull(o["defaultFilterSetting"]),
            defaultEspressoSetting = Lenient.numOrNull(o["defaultEspressoSetting"]),
            notes = Lenient.str(o["notes"]),
            isDefault = Lenient.bool(o["isDefault"]),
            isArchived = Lenient.bool(o["isArchived"]),
            updatedAt = BackupTime.canonical(o["updatedAt"]),
        )
    }

    private fun taste(v: JsonElement?): TasteDto {
        val o = Lenient.obj(v) ?: JsonObject(emptyMap())
        fun axis(key: String): Int = Lenient.int(o[key], 5).coerceIn(0, 10)
        return TasteDto(
            acidity = axis("acidity"),
            sweetness = axis("sweetness"),
            bitterness = axis("bitterness"),
            body = axis("body"),
            balance = axis("balance"),
            aftertaste = axis("aftertaste"),
            clarity = axis("clarity"),
        )
    }

    /** Null when the brew has no uid or no readable start time. */
    fun decodeBrew(o: JsonObject): BrewDto? {
        val uid = Lenient.uid(o["uid"]) ?: return null
        val startedAt = BackupTime.canonical(o["startedAt"]) ?: return null
        val bed = Lenient.obj(o["bed"]) ?: JsonObject(emptyMap())
        return BrewDto(
            uid = uid,
            startedAt = startedAt,
            updatedAt = BackupTime.canonical(o["updatedAt"]),
            recipeId = Lenient.str(o["recipeId"]),
            recipeName = Lenient.str(o["recipeName"]),
            recipeAuthor = Lenient.str(o["recipeAuthor"]),
            brewerId = Lenient.strOrNull(o["brewerId"]),
            brewerLabel = Lenient.str(o["brewerLabel"]),
            beanUid = Lenient.uid(o["beanUid"]),
            beanLabel = Lenient.str(o["beanLabel"]),
            daysOffRoast = Lenient.intOrNull(o["daysOffRoast"]),
            grinderUid = Lenient.uid(o["grinderUid"]),
            grinderLabel = Lenient.str(o["grinderLabel"]),
            grindSetting = Lenient.str(o["grindSetting"]),
            grindClicks = Lenient.intOrNull(o["grindClicks"]),
            grindValue = Lenient.numOrNull(o["grindValue"]),
            grindKind = Lenient.oneOf(o["grindKind"], GRIND_KINDS) ?: "",
            doseG = Lenient.num(o["doseG"]),
            waterG = Lenient.num(o["waterG"]),
            tempC = Lenient.int(o["tempC"]),
            waterId = Lenient.strOrNull(o["waterId"]),
            totalTimeSec = Lenient.int(o["totalTimeSec"]),
            drawdownSec = Lenient.intOrNull(o["drawdownSec"]),
            beverageG = Lenient.numOrNull(o["beverageG"]),
            tdsPercent = Lenient.numOrNull(o["tdsPercent"]),
            extractionYield = Lenient.numOrNull(o["extractionYield"]),
            flow = BackupIds.flowId(Lenient.str(o["flow"])),
            bed = BedDto(
                shape = BackupIds.bedId(Lenient.str(bed["shape"])),
                surface = BackupIds.sludgeId(Lenient.str(bed["surface"])),
                channelling = BackupIds.channellingId(Lenient.str(bed["channelling"])),
                notes = Lenient.str(bed["notes"]),
            ),
            taste = taste(o["taste"]),
            overall = Lenient.int(o["overall"]).coerceIn(0, 10),
            descriptors = Lenient.strList(o["descriptors"]),
            faults = Lenient.strList(o["faults"]),
            notes = Lenient.str(o["notes"]),
            nextTime = Lenient.str(o["nextTime"]),
            isFavourite = Lenient.bool(o["isFavourite"]),
            sharedAt = BackupTime.canonical(o["sharedAt"]),
        )
    }

    /** The entry with a trimmed id and a readable updatedAt (or null); null when it has no id. */
    fun decodeRecipe(o: JsonObject): JsonObject? {
        val id = Lenient.str(o["id"]).trim()
        if (id.isEmpty()) return null
        val stamp = BackupTime.canonical(o["updatedAt"])
        return JsonObject(o + mapOf("id" to JsonPrimitive(id), "updatedAt" to (stamp?.let { JsonPrimitive(it) } ?: JsonNull)))
    }

    fun decodeAnalysis(o: JsonObject, defaultOrigin: String): AnalysisDto? {
        val uid = Lenient.uid(o["uid"]) ?: return null
        val kind = Lenient.oneOf(o["kind"], listOf("brew", "trend")) ?: return null
        val createdAt = BackupTime.canonical(o["createdAt"]) ?: return null
        return AnalysisDto(
            uid = uid,
            kind = kind,
            brewUid = if (kind == "brew") Lenient.uid(o["brewUid"]) else null,
            createdAt = createdAt,
            origin = Lenient.str(o["origin"]).ifEmpty { defaultOrigin },
            promptVersion = Lenient.int(o["promptVersion"], 1),
            inputHash = Lenient.str(o["inputHash"]),
            brewCount = Lenient.int(o["brewCount"]),
            newestBrewAt = BackupTime.canonical(o["newestBrewAt"]),
            providerId = Lenient.str(o["providerId"]).ifEmpty { "custom" },
            model = Lenient.str(o["model"]),
            host = Lenient.str(o["host"]),
            rawText = Lenient.str(o["rawText"]),
            result = o["result"]?.takeIf { it !is JsonNull },
            flags = (o["flags"] as? JsonArray)?.toList().orEmpty(),
            promptTokens = Lenient.intOrNull(o["promptTokens"]),
            completionTokens = Lenient.intOrNull(o["completionTokens"]),
            costMicroUsd = Lenient.longOrNull(o["costMicroUsd"]),
            latencyMs = Lenient.longOrNull(o["latencyMs"]) ?: 0L,
            photoCount = Lenient.int(o["photoCount"]),
        )
    }

    fun decodePhoto(o: JsonObject): PhotoDto? {
        val uid = Lenient.uid(o["uid"]) ?: return null
        val brewUid = Lenient.uid(o["brewUid"])
        val beanUid = Lenient.uid(o["beanUid"])
        if (brewUid == null && beanUid == null) return null
        val createdAt = BackupTime.canonical(o["createdAt"])
        val takenAt = BackupTime.canonical(o["takenAt"]) ?: createdAt ?: return null
        return PhotoDto(
            uid = uid,
            brewUid = brewUid,
            beanUid = if (brewUid != null) null else beanUid,
            kind = Lenient.oneOf(o["kind"], BackupIds.PHOTO_KINDS) ?: "other",
            takenAt = takenAt,
            createdAt = createdAt ?: takenAt,
            caption = Lenient.str(o["caption"]),
            sendToAi = Lenient.bool(o["sendToAi"], true),
            widthPx = Lenient.int(o["widthPx"]),
            heightPx = Lenient.int(o["heightPx"]),
            mediaType = "image/jpeg",
            data = Lenient.strOrNull(o["data"]),
        )
    }

    fun decodeSettings(v: JsonElement?): SettingsDto? {
        val o = Lenient.obj(v) ?: return null
        fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return SettingsDto(
            tempUnit = Lenient.oneOf(o["tempUnit"], listOf("c", "f")),
            weightUnit = Lenient.oneOf(o["weightUnit"], listOf("g", "oz")),
            darkMode = Lenient.oneOf(o["darkMode"], listOf("system", "light", "dark")),
            haptics = Lenient.boolOrNull(o["haptics"]),
            soundCues = Lenient.boolOrNull(o["soundCues"]),
            keepScreenOn = Lenient.boolOrNull(o["keepScreenOn"]),
            autoAdvanceSteps = Lenient.boolOrNull(o["autoAdvanceSteps"]),
            name = text("name"),
            ownedBrewerIds = (o["ownedBrewerIds"] as? JsonArray)?.let { Lenient.strList(it) },
            favouriteRoasterIds = (o["favouriteRoasterIds"] as? JsonArray)?.let { Lenient.strList(it) },
            homeArea = text("homeArea"),
            defaultWaterId = text("defaultWaterId"),
            defaultBeanUid = Lenient.uid(o["defaultBeanUid"]),
            defaultGrinderUid = Lenient.uid(o["defaultGrinderUid"]),
            lastRecipeId = text("lastRecipeId"),
            onboardingDone = Lenient.boolOrNull(o["onboardingDone"]),
            // "" is a real value here ("not asked yet"), so a missing key must not read as it.
            experience = text("experience")?.takeIf { it in listOf("", "new", "recipes", "tinker") },
            hasScale = Lenient.boolOrNull(o["hasScale"]),
            reviewMode = Lenient.oneOf(o["reviewMode"], listOf("quick", "detailed")),
            buysGround = Lenient.boolOrNull(o["buysGround"]),
            hasDefaultBean = "defaultBeanUid" in o,
            hasDefaultGrinder = "defaultGrinderUid" in o,
        )
    }

    fun decodeAi(v: JsonElement?): AiDto? {
        val o = Lenient.obj(v) ?: return null
        val providers = (o["providers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = Lenient.obj(e) ?: return@mapNotNull null
            val id = Lenient.str(p["id"]).trim()
            if (id.isEmpty()) return@mapNotNull null
            AiProviderDto(
                id = id,
                baseUrl = if (id == "custom") Lenient.strOrNull(p["baseUrl"]) else null,
                model = Lenient.str(p["model"]),
                vision = Lenient.boolOrNull(p["vision"]),
            )
        }
        return AiDto(
            activeProvider = Lenient.strOrNull(o["activeProvider"]),
            sendPhotos = Lenient.bool(o["sendPhotos"], true),
            providers = providers,
        )
    }

    private fun <T> list(
        raw: JsonElement?,
        name: String,
        problems: MutableList<String>,
        why: (JsonObject) -> String,
        decode: (JsonObject) -> T?,
    ): List<T> {
        val array = raw as? JsonArray ?: return emptyList()
        val out = ArrayList<T>(array.size)
        array.forEachIndexed { i, item ->
            val o = item as? JsonObject
            val value = o?.let(decode)
            if (value != null) out += value else problems += "$name[$i]: " + (o?.let(why) ?: "not an object")
        }
        return out
    }

    private fun whyNot(o: JsonObject, vararg needs: Pair<String, (JsonObject) -> Boolean>): String =
        needs.firstOrNull { (_, ok) -> !ok(o) }?.first ?: "couldn't be read"

    private val hasUid: Pair<String, (JsonObject) -> Boolean> = "no uid" to { o -> Lenient.uid(o["uid"]) != null }

    /** The platform a file says it came from; analyses with no origin of their own take it. */
    fun platformOf(root: JsonObject): String = Lenient.str(Lenient.obj(root["app"])?.get("platform")).ifEmpty { "android" }

    /** Why [o] is not a photo this app can place, or null when it is one. */
    internal fun photoProblem(o: JsonObject): String? =
        if (decodePhoto(o) != null) null
        else whyNot(
            o,
            hasUid,
            "belongs to no brew or coffee" to { p -> Lenient.uid(p["brewUid"]) != null || Lenient.uid(p["beanUid"]) != null },
            "no takenAt" to { p -> BackupTime.parse(p["takenAt"]) != null || BackupTime.parse(p["createdAt"]) != null },
        )

    /** Lenient decode of a value [detect] called V2. */
    fun decodeV2(root: JsonObject): Decoded {
        val problems = mutableListOf<String>()
        val app = Lenient.obj(root["app"]) ?: JsonObject(emptyMap())
        val options = Lenient.obj(root["options"]) ?: JsonObject(emptyMap())
        val platform = platformOf(root)
        val doc = BackupDoc(
            format = Backup.FORMAT,
            version = Lenient.int(root["version"], Backup.VERSION),
            exportedAt = BackupTime.canonical(root["exportedAt"]).orEmpty(),
            app = BackupApp(
                platform = platform,
                appVersion = Lenient.str(app["appVersion"]),
                packageName = Lenient.str(app["packageName"]),
                dbVersion = Lenient.int(app["dbVersion"]),
            ),
            options = BackupOptions(
                includesPhotos = Lenient.bool(options["includesPhotos"]),
                includesAnalyses = Lenient.bool(options["includesAnalyses"]),
            ),
            settings = decodeSettings(root["settings"]),
            ai = decodeAi(root["ai"]),
            beans = list(root["beans"], "beans", problems, { whyNot(it, hasUid, "no name" to { o -> Lenient.str(o["name"]).isNotBlank() }) }, ::decodeBean),
            grinders = list(root["grinders"], "grinders", problems, { whyNot(it, hasUid) }, ::decodeGrinder),
            brews = list(root["brews"], "brews", problems, { whyNot(it, hasUid, "no startedAt" to { o -> BackupTime.parse(o["startedAt"]) != null }) }, ::decodeBrew),
            recipes = list(root["recipes"], "recipes", problems, { "no id" }, ::decodeRecipe),
            analyses = list(
                root["analyses"], "analyses", problems,
                {
                    whyNot(
                        it, hasUid,
                        "unknown kind" to { o -> Lenient.oneOf(o["kind"], listOf("brew", "trend")) != null },
                        "no createdAt" to { o -> BackupTime.parse(o["createdAt"]) != null },
                    )
                },
            ) { decodeAnalysis(it, platform) },
            photos = list(root["photos"], "photos", problems, { photoProblem(it) ?: "couldn't be read" }, ::decodePhoto),
        )
        return Decoded(doc, problems)
    }

    // ------------------------------------------------------------------ encode

    fun <T> encode(serializer: KSerializer<T>, value: T): String = writer.encodeToString(serializer, value)

    /** The whole document, photos included as they are in [doc]. Large backups go through BackupWriter. */
    fun encode(doc: BackupDoc): String = writer.encodeToString(BackupDoc.serializer(), doc)
}
