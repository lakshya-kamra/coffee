package com.laksh.coffee.domain.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.TasteProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Everything a backup is made from, read in one go. */
data class BackupSnapshot(
    val beans: List<BeanEntity>,
    val grinders: List<GrinderEntity>,
    val brews: List<BrewLogEntity>,
    val savedRecipes: List<SavedRecipeEntity>,
    val analyses: List<AnalysisEntity>,
    val photos: List<PhotoEntity>,
    val settings: Settings,
    val ai: AiDto?,
)

/**
 * Database rows to backup records and back. Local numeric ids never leave the phone: on the way
 * out they become uids, on the way in the caller hands over the ids it found or made. Pure.
 */
object BackupMapping {

    private fun iso(ms: Long?): String? = ms?.takeIf { it > 0 }?.let(BackupTime::format)
    private fun ms(text: String?): Long? = BackupTime.parse(text)

    // ============================================================== rows -> records

    fun bean(b: BeanEntity): BeanDto = BeanDto(
        uid = b.uid,
        name = b.name,
        roaster = b.roaster,
        roasterId = b.roasterId,
        origin = b.origin,
        originId = b.originId,
        region = b.region,
        farm = b.farm,
        producer = b.producer,
        washingStation = b.washingStation,
        lotTypeId = b.lotTypeId,
        speciesId = b.speciesId,
        varietalIds = b.varietalIds,
        varietal = b.varietal,
        processId = b.processId,
        isDecaf = b.isDecaf,
        decafMethodId = b.decafMethodId,
        roastLevelId = b.roastLevelId,
        roastIntentId = b.roastIntentId,
        altitudeM = b.altitudeMasl,
        harvest = b.harvest,
        roastedOn = b.roastedOnEpochDay?.let(BackupTime::date),
        openedOn = b.openedOnEpochDay?.let(BackupTime::date),
        restDays = b.restDays,
        bagSizeG = b.bagSizeG?.finite(),
        priceAmount = b.priceAmount?.finite(),
        currency = b.currency,
        priceText = b.price,
        tastingNotes = b.tastingNotes,
        notes = b.notes,
        isArchived = b.isArchived,
        createdAt = iso(b.createdAt),
        updatedAt = iso(b.updatedAt),
    )

    fun grinder(g: GrinderEntity): GrinderDto = GrinderDto(
        uid = g.uid,
        catalogId = g.catalogId,
        brand = g.brand,
        model = g.model,
        burr = BackupIds.burr(g.burrType),
        burrSizeMm = g.burrSizeMm,
        setting = GrinderSettingDto(
            style = BackupIds.style(g.settingStyle),
            min = g.minSetting.finite() ?: 0.0,
            max = g.maxSetting.finite() ?: 40.0,
            step = g.step.finite() ?: 1.0,
            lowerIsFiner = g.lowerIsFiner,
            clicksPerRotation = g.clicksPerRotation.takeIf { it > 0 },
            micronsPerStep = g.micronsPerClick?.finite(),
            dialFormat = g.dialFormat,
        ),
        defaultFilterSetting = g.defaultFilterSetting?.finite(),
        defaultEspressoSetting = g.defaultEspressoSetting?.finite(),
        notes = g.notes,
        isDefault = g.isDefault,
        isArchived = g.isArchived,
        updatedAt = iso(g.updatedAt),
    )

    fun brew(l: BrewLogEntity, beanUid: (Long) -> String?, grinderUid: (Long) -> String?): BrewDto = BrewDto(
        uid = l.uid,
        startedAt = BackupTime.format(l.startedAt),
        updatedAt = iso(l.updatedAt),
        recipeId = l.recipeId,
        recipeName = l.recipeName,
        recipeAuthor = l.recipeAuthor,
        brewerId = l.brewerId,
        brewerLabel = l.brewer,
        beanUid = l.beanId?.let(beanUid),
        beanLabel = l.beanLabel,
        daysOffRoast = l.daysOffRoast?.toInt(),
        grinderUid = l.grinderId?.let(grinderUid),
        grinderLabel = l.grinderLabel,
        grindSetting = l.grindSetting,
        grindClicks = l.grindClicks,
        grindValue = l.grindValue?.finite(),
        doseG = l.doseG.finite() ?: 0.0,
        waterG = l.waterG.finite() ?: 0.0,
        tempC = l.tempC,
        waterId = l.waterId,
        totalTimeSec = l.totalTimeSec,
        drawdownSec = l.drawdownSec,
        beverageG = l.beverageG?.finite(),
        tdsPercent = l.tdsPercent?.finite(),
        extractionYield = l.extractionYield?.finite(),
        flow = BackupIds.flow(l.flowFeel),
        bed = BedDto(
            shape = BackupIds.bed(l.bedShape),
            surface = BackupIds.sludge(l.sludge),
            channelling = BackupIds.channelling(l.channeling),
            notes = l.bedNotes,
        ),
        taste = TasteDto(
            acidity = l.taste.acidity,
            sweetness = l.taste.sweetness,
            bitterness = l.taste.bitterness,
            body = l.taste.body,
            balance = l.taste.balance,
            aftertaste = l.taste.aftertaste,
            clarity = l.taste.clarity,
        ),
        overall = l.overall,
        descriptors = l.descriptors,
        faults = l.faults,
        notes = l.notes,
        nextTime = l.nextTime,
        isFavourite = l.isFavourite,
        sharedAt = iso(l.sharedAt),
    )

    private val recipeJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** The saved recipe as a shared/recipes.json entry plus updatedAt; null when the row cannot be read. */
    fun recipe(row: SavedRecipeEntity): JsonObject? {
        val recipe = runCatching { recipeJson.decodeFromString(Recipe.serializer(), row.json) }.getOrNull() ?: return null
        val entry = recipeJson.encodeToJsonElement(Recipe.serializer(), recipe.copy(id = row.id, isBuiltIn = false)).jsonObject
        return JsonObject(entry + ("updatedAt" to (iso(row.updatedAt)?.let { JsonPrimitive(it) } ?: JsonNull)))
    }

    fun analysis(a: AnalysisEntity, brewUid: (Long) -> String?): AnalysisDto = AnalysisDto(
        uid = a.uid,
        kind = a.kind,
        brewUid = if (a.kind == AnalysisEntity.KIND_BREW) a.brewLogId?.let(brewUid) else null,
        createdAt = BackupTime.format(a.createdAt),
        origin = a.origin.ifEmpty { "android" },
        promptVersion = a.promptVersion,
        inputHash = a.inputHash,
        brewCount = a.brewCount,
        newestBrewAt = iso(a.newestBrewAt),
        providerId = a.providerId.ifEmpty { "custom" },
        model = a.model,
        host = a.host,
        rawText = a.rawText,
        result = a.resultJson?.let(::element)?.takeIf { it !is JsonNull }?.let { withBrewUids(it, a.origin.ifEmpty { "android" } == "android", brewUid) },
        flags = (element(a.flagsJson) as? JsonArray)?.toList().orEmpty(),
        promptTokens = a.promptTokens,
        completionTokens = a.completionTokens,
        costMicroUsd = a.costMicroUsd,
        latencyMs = a.latencyMs,
        photoCount = a.photoCount,
    )

    private fun element(json: String): JsonElement? = runCatching { BackupCodec.compact.parseToJsonElement(json) }.getOrNull()

    private const val BREW_UIDS = "brewUids"

    /** The brew numbers a stored trend's patterns name; null when [result] holds no trend. */
    private fun trendBrewNumbers(result: JsonElement): List<Long>? {
        val trend = (result as? JsonObject)?.get("trend") as? JsonObject ?: return null
        return (trend["patterns"] as? JsonArray).orEmpty()
            .flatMap { ((it as? JsonObject)?.get("brewIds") as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
            .distinct()
    }

    /**
     * A trend stored before results kept brew uids is given them on the way out, while its
     * numbers are still this phone's row ids. In the file they would mean nothing. One that
     * another app made ([madeHere] false) never held this phone's row ids: it gets an empty table.
     */
    private fun withBrewUids(result: JsonElement, madeHere: Boolean, brewUid: (Long) -> String?): JsonElement {
        val numbers = trendBrewNumbers(result) ?: return result
        val o = result as JsonObject
        if (BREW_UIDS in o) return result
        if (!madeHere) return JsonObject(o + (BREW_UIDS to JsonObject(emptyMap())))
        val uids = numbers.mapNotNull { n -> brewUid(n)?.let { n.toString() to JsonPrimitive(it) } }.toMap()
        return JsonObject(o + (BREW_UIDS to JsonObject(uids)))
    }

    /**
     * A trend that arrives with no brew uids names its brews by another install's row ids. An
     * empty table says so: none of its numbers is followed, where a guess could open the wrong brew.
     */
    private fun withoutForeignBrewNumbers(result: JsonElement): JsonElement {
        trendBrewNumbers(result) ?: return result
        val o = result as JsonObject
        return if (BREW_UIDS in o) result else JsonObject(o + (BREW_UIDS to JsonObject(emptyMap())))
    }

    /** [data] is the stored file as base64, or null for a backup saved without photos. */
    fun photo(p: PhotoEntity, brewUid: (Long) -> String?, beanUid: (Long) -> String?, data: String? = null): PhotoDto {
        val brew = p.brewLogId?.let(brewUid)
        return PhotoDto(
            uid = p.uid,
            brewUid = brew,
            beanUid = if (p.brewLogId == null) p.beanId?.let(beanUid) else null,
            kind = p.kind.takeIf { it in BackupIds.PHOTO_KINDS } ?: "other",
            takenAt = BackupTime.format(p.takenAt),
            createdAt = BackupTime.format(p.createdAt),
            caption = p.caption,
            sendToAi = p.sendToAi,
            widthPx = p.widthPx,
            heightPx = p.heightPx,
            mediaType = "image/jpeg",
            data = data,
        )
    }

    fun settings(s: Settings, beanUid: (Long) -> String?, grinderUid: (Long) -> String?): SettingsDto = SettingsDto(
        tempUnit = s.tempUnit,
        weightUnit = s.weightUnit,
        darkMode = s.darkMode,
        haptics = s.haptics,
        soundCues = s.soundCues,
        keepScreenOn = s.keepScreenOn,
        autoAdvanceSteps = s.autoAdvanceSteps,
        name = s.name,
        ownedBrewerIds = s.ownedBrewerIds.sorted(),
        favouriteRoasterIds = s.favouriteRoasterIds.sorted(),
        homeArea = s.homeArea,
        defaultWaterId = s.defaultWaterId,
        defaultBeanUid = s.defaultBeanId.takeIf { it > 0 }?.let(beanUid),
        defaultGrinderUid = s.defaultGrinderId.takeIf { it > 0 }?.let(grinderUid),
        lastRecipeId = s.lastRecipeId,
        onboardingDone = s.onboardingDone,
        hasDefaultBean = true,
        hasDefaultGrinder = true,
    )

    /**
     * The whole document with no photo data. Photos are listed (so a backup saved without them
     * still says they existed); the writer fills in each one's bytes as it streams.
     */
    fun document(
        s: BackupSnapshot,
        exportedAt: Long,
        app: BackupApp,
        includesPhotos: Boolean,
        includesAnalyses: Boolean,
    ): BackupDoc {
        val beanUids = s.beans.associate { it.id to it.uid }
        val grinderUids = s.grinders.associate { it.id to it.uid }
        val brewUids = s.brews.associate { it.id to it.uid }
        return BackupDoc(
            exportedAt = BackupTime.format(exportedAt),
            app = app,
            options = BackupOptions(includesPhotos = includesPhotos, includesAnalyses = includesAnalyses),
            settings = settings(s.settings, beanUids::get, grinderUids::get),
            ai = s.ai,
            beans = s.beans.map { bean(it) },
            grinders = s.grinders.map { grinder(it) },
            brews = s.brews.sortedBy { it.startedAt }.map { brew(it, beanUids::get, grinderUids::get) },
            recipes = s.savedRecipes.mapNotNull { recipe(it) },
            analyses = if (includesAnalyses) s.analyses.map { analysis(it, brewUids::get) } else emptyList(),
            // A photo whose brew or coffee is gone would import as a photo of nothing.
            photos = s.photos.map { photo(it, brewUids::get, beanUids::get) }.filter { it.brewUid != null || it.beanUid != null },
        )
    }

    // ============================================================== records -> rows

    fun bean(d: BeanDto, fallbackMs: Long, id: Long = 0): BeanEntity {
        val createdAt = ms(d.createdAt) ?: ms(d.updatedAt) ?: fallbackMs
        return BeanEntity(
            id = id,
            name = TextLimits.label(d.name),
            roaster = TextLimits.label(d.roaster),
            origin = TextLimits.label(d.origin),
            region = TextLimits.label(d.region),
            varietal = TextLimits.label(d.varietal),
            processId = TextLimits.label(d.processId),
            roastLevelId = TextLimits.label(d.roastLevelId),
            roastedOnEpochDay = BackupTime.epochDay(d.roastedOn),
            openedOnEpochDay = BackupTime.epochDay(d.openedOn),
            bagSizeG = d.bagSizeG,
            price = TextLimits.label(d.priceText),
            altitudeMasl = TextLimits.label(d.altitudeM),
            tastingNotes = TextLimits.note(d.tastingNotes),
            notes = TextLimits.note(d.notes),
            isArchived = d.isArchived,
            createdAt = createdAt,
            uid = d.uid,
            updatedAt = ms(d.updatedAt) ?: createdAt,
            roasterId = TextLimits.labelOrNull(d.roasterId),
            originId = TextLimits.labelOrNull(d.originId),
            farm = TextLimits.label(d.farm),
            producer = TextLimits.label(d.producer),
            washingStation = TextLimits.label(d.washingStation),
            lotTypeId = TextLimits.labelOrNull(d.lotTypeId),
            speciesId = TextLimits.labelOrNull(d.speciesId),
            varietalIds = TextLimits.words(d.varietalIds),
            isDecaf = d.isDecaf,
            decafMethodId = TextLimits.labelOrNull(d.decafMethodId),
            roastIntentId = TextLimits.labelOrNull(d.roastIntentId),
            harvest = TextLimits.label(d.harvest),
            restDays = d.restDays,
            priceAmount = d.priceAmount,
            currency = TextLimits.label(d.currency),
        )
    }

    fun grinder(d: GrinderDto, fallbackMs: Long, id: Long = 0): GrinderEntity = GrinderEntity(
        id = id,
        brand = TextLimits.label(d.brand),
        model = TextLimits.label(d.model),
        burrType = BackupIds.burr(d.burr),
        burrSizeMm = TextLimits.label(d.burrSizeMm),
        settingStyle = BackupIds.style(d.setting.style),
        clicksPerRotation = d.setting.clicksPerRotation ?: 0,
        micronsPerClick = d.setting.micronsPerStep,
        lowerIsFiner = d.setting.lowerIsFiner,
        minSetting = d.setting.min,
        maxSetting = d.setting.max,
        step = d.setting.step,
        defaultFilterSetting = d.defaultFilterSetting,
        defaultEspressoSetting = d.defaultEspressoSetting,
        notes = TextLimits.note(d.notes),
        isDefault = d.isDefault,
        isArchived = d.isArchived,
        uid = d.uid,
        updatedAt = ms(d.updatedAt) ?: fallbackMs,
        catalogId = TextLimits.labelOrNull(d.catalogId),
        dialFormat = TextLimits.label(d.setting.dialFormat),
    )

    fun brew(d: BrewDto, beanId: Long?, grinderId: Long?, id: Long = 0): BrewLogEntity {
        val startedAt = ms(d.startedAt) ?: 0L
        return BrewLogEntity(
            id = id,
            startedAt = startedAt,
            recipeId = TextLimits.label(d.recipeId),
            recipeName = TextLimits.label(d.recipeName),
            recipeAuthor = TextLimits.label(d.recipeAuthor),
            brewer = TextLimits.label(d.brewerLabel),
            beanId = beanId,
            beanLabel = TextLimits.label(d.beanLabel),
            daysOffRoast = d.daysOffRoast?.toLong(),
            grinderId = grinderId,
            grinderLabel = TextLimits.label(d.grinderLabel),
            grindSetting = TextLimits.label(d.grindSetting),
            grindClicks = d.grindClicks,
            doseG = d.doseG,
            waterG = d.waterG,
            tempC = d.tempC,
            totalTimeSec = d.totalTimeSec,
            drawdownSec = d.drawdownSec,
            beverageG = d.beverageG,
            tdsPercent = d.tdsPercent,
            extractionYield = d.extractionYield,
            flowFeel = BackupIds.flow(d.flow),
            bedShape = BackupIds.bed(d.bed.shape),
            sludge = BackupIds.sludge(d.bed.surface),
            channeling = BackupIds.channelling(d.bed.channelling),
            bedNotes = TextLimits.note(d.bed.notes),
            taste = TasteProfile(
                acidity = d.taste.acidity.coerceIn(0, 10),
                sweetness = d.taste.sweetness.coerceIn(0, 10),
                bitterness = d.taste.bitterness.coerceIn(0, 10),
                body = d.taste.body.coerceIn(0, 10),
                balance = d.taste.balance.coerceIn(0, 10),
                aftertaste = d.taste.aftertaste.coerceIn(0, 10),
                clarity = d.taste.clarity.coerceIn(0, 10),
            ),
            overall = d.overall.coerceIn(0, 10),
            descriptors = TextLimits.words(d.descriptors),
            faults = TextLimits.words(d.faults),
            notes = TextLimits.note(d.notes),
            nextTime = TextLimits.note(d.nextTime),
            isFavourite = d.isFavourite,
            sharedAt = ms(d.sharedAt),
            uid = d.uid,
            updatedAt = ms(d.updatedAt) ?: startedAt,
            brewerId = TextLimits.labelOrNull(d.brewerId),
            grindValue = d.grindValue,
            waterId = TextLimits.labelOrNull(d.waterId),
        )
    }

    /** Null when the entry is not a recipe this app can open (no name, no steps, numbers missing). */
    fun savedRecipe(entry: JsonObject, fallbackMs: Long): SavedRecipeEntity? {
        val id = Lenient.str(entry["id"]).trim()
        if (id.isEmpty() || id.length > 128) return null
        val recipe = runCatching {
            recipeJson.decodeFromJsonElement(Recipe.serializer(), wholeNumbers(JsonObject(entry - "updatedAt")))
        }.getOrNull() ?: return null
        if (recipe.name.isBlank() || recipe.steps.isEmpty()) return null
        if (!recipe.doseG.isFinite() || !recipe.waterG.isFinite()) return null
        val json = BackupCodec.compact.encodeToString(Recipe.serializer(), recipe.copy(id = id, isBuiltIn = false))
        // A recipe is a few thousand characters. One this long is not a recipe, and its row
        // could be too big to read back.
        if (json.length > TextLimits.RECIPE_JSON) return null
        return SavedRecipeEntity(
            id = id,
            json = json,
            updatedAt = BackupTime.parse(entry["updatedAt"]) ?: fallbackMs,
        )
    }

    // A file written by JavaScript may hold 94.0 where this app's recipe holds a whole number.
    private fun wholeNumbers(o: JsonObject): JsonObject {
        fun whole(v: JsonElement?): JsonElement? = Lenient.longOrNull(v)?.let { JsonPrimitive(it) } ?: v
        val steps = (o["steps"] as? JsonArray)?.map { s ->
            val step = s as? JsonObject ?: return@map s
            JsonObject(step + listOf("startSec", "endSec", "targetWaterG").mapNotNull { k -> whole(step[k])?.let { k to it } })
        }
        val out = o.toMutableMap()
        whole(o["tempC"])?.let { out["tempC"] = it }
        if (steps != null) out["steps"] = JsonArray(steps)
        return JsonObject(out)
    }

    fun analysis(d: AnalysisDto, brewLogId: Long?): AnalysisEntity = AnalysisEntity(
        kind = d.kind,
        brewLogId = brewLogId,
        createdAt = ms(d.createdAt) ?: 0L,
        promptVersion = d.promptVersion,
        inputHash = TextLimits.label(d.inputHash),
        brewCount = d.brewCount,
        newestBrewAt = ms(d.newestBrewAt) ?: 0L,
        model = TextLimits.label(d.model),
        host = TextLimits.label(d.host),
        rawText = TextLimits.clip(d.rawText, TextLimits.REPLY),
        // Cut JSON is not JSON: an oversized result reads as a reply that did not parse, and
        // the reply's own text is still shown.
        resultJson = d.result?.takeIf { it !is JsonNull }?.let(::withoutForeignBrewNumbers)?.toString()
            ?.takeIf { it.length <= TextLimits.RESULT_JSON },
        flagsJson = JsonArray(d.flags).toString().takeIf { it.length <= TextLimits.FLAGS_JSON } ?: "[]",
        promptTokens = d.promptTokens,
        completionTokens = d.completionTokens,
        costMicroUsd = d.costMicroUsd,
        latencyMs = d.latencyMs,
        uid = d.uid,
        providerId = TextLimits.label(d.providerId),
        photoCount = d.photoCount,
        // Kept as written: an analysis made on the web reads "Made on another device" here.
        origin = TextLimits.label(d.origin),
    )

    fun photo(d: PhotoDto, brewLogId: Long?, beanId: Long?, fileName: String, bytes: Long, widthPx: Int, heightPx: Int): PhotoEntity {
        val takenAt = ms(d.takenAt) ?: 0L
        return PhotoEntity(
            uid = d.uid,
            brewLogId = brewLogId,
            beanId = if (brewLogId == null) beanId else null,
            kind = d.kind.takeIf { it in BackupIds.PHOTO_KINDS } ?: "other",
            fileName = fileName,
            widthPx = widthPx,
            heightPx = heightPx,
            bytes = bytes,
            takenAt = takenAt,
            createdAt = ms(d.createdAt) ?: takenAt,
            caption = d.caption.trim().take(MAX_CAPTION),
            sendToAi = d.sendToAi,
        )
    }

    /** Same limit as PhotoRepository.MAX_CAPTION. */
    private const val MAX_CAPTION = 200

    /** [cur] with only the keys the file carries changed; uids become this phone's ids (0 = none). */
    fun settings(cur: Settings, d: SettingsDto, beanId: (String) -> Long?, grinderId: (String) -> Long?): Settings = cur.copy(
        tempUnit = d.tempUnit ?: cur.tempUnit,
        weightUnit = d.weightUnit ?: cur.weightUnit,
        darkMode = d.darkMode ?: cur.darkMode,
        haptics = d.haptics ?: cur.haptics,
        soundCues = d.soundCues ?: cur.soundCues,
        keepScreenOn = d.keepScreenOn ?: cur.keepScreenOn,
        autoAdvanceSteps = d.autoAdvanceSteps ?: cur.autoAdvanceSteps,
        name = d.name?.take(40) ?: cur.name,
        ownedBrewerIds = d.ownedBrewerIds?.toSet() ?: cur.ownedBrewerIds,
        favouriteRoasterIds = d.favouriteRoasterIds?.toSet() ?: cur.favouriteRoasterIds,
        homeArea = d.homeArea ?: cur.homeArea,
        defaultWaterId = d.defaultWaterId ?: cur.defaultWaterId,
        defaultBeanId = if (d.hasDefaultBean) d.defaultBeanUid?.let(beanId) ?: 0L else cur.defaultBeanId,
        defaultGrinderId = if (d.hasDefaultGrinder) d.defaultGrinderUid?.let(grinderId) ?: 0L else cur.defaultGrinderId,
        lastRecipeId = d.lastRecipeId ?: cur.lastRecipeId,
        // Never un-finishes setup: a backup from a phone that skipped it must not start it here.
        onboardingDone = cur.onboardingDone || d.onboardingDone == true,
    )

    private fun Double.finite(): Double? = takeIf { it.isFinite() }
}
