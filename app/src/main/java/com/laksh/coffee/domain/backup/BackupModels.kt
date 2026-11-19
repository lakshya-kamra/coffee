package com.laksh.coffee.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Coffee backup v2 (shared/schema/backup-v2.md): the shapes exactly as they are written to and
 * read from the file. Instants are ISO-8601 UTC strings, calendar dates "YYYY-MM-DD". Numeric
 * database ids never appear; records link by uid. The property order here is the key order in
 * the file, the same order the web app writes.
 */

object Backup {
    const val FORMAT = "coffee-backup"
    const val VERSION = 2
    const val LEGACY_SCHEMA = "coffee-brew-log/1"

    /** A bigger file would need several times its size in memory on a phone. */
    const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    /**
     * The biggest file a writer makes. Both apps refuse to read more than [MAX_IMPORT_BYTES], so
     * a backup over it could never be restored; the margin covers the sums being a little off.
     * The web app's MAX_BACKUP_BYTES is the same number.
     */
    const val MAX_BACKUP_BYTES = MAX_IMPORT_BYTES - 2L * 1024 * 1024

    /** uid: an opaque string of 1 to 64 characters. */
    const val UID_MAX = 64
}

/** Which photos keep their image when a backup cannot hold them all. Pure; the web app's rule. */
object PhotoBudget {

    /** [bytes] is what the image takes in the file, as base64. */
    data class Sized(val uid: String, val createdAtMs: Long, val bytes: Long)

    /** Bytes a stored photo takes in the file as base64. */
    fun base64Size(bytes: Long): Long = (bytes + 2) / 3 * 4

    /**
     * The newest first, and a photo that does not fit is passed over so a smaller, older one
     * still can.
     */
    fun thatFit(photos: List<Sized>, budget: Long): Set<String> {
        val keep = HashSet<String>()
        var left = budget
        for (p in photos.sortedByDescending { it.createdAtMs }) {
            if (p.bytes > left) continue
            left -= p.bytes
            keep += p.uid
        }
        return keep
    }
}

/**
 * The longest text an import stores in one field. Far above anything either app writes, and low
 * enough that a row always fits the 2 MB window Android reads a row through: a longer row can
 * be written but never read back, and every screen that lists it would stay empty.
 */
internal object TextLimits {
    /** Names, labels and ids. */
    const val LABEL = 500

    /** Notes. */
    const val NOTE = 20_000

    /** Entries in a list of words (descriptors, faults, varietals). */
    const val LIST = 64

    /** A model's reply as it arrived. */
    const val REPLY = 300_000

    /** A parsed analysis result. */
    const val RESULT_JSON = 150_000
    const val FLAGS_JSON = 20_000

    /** A saved recipe. */
    const val RECIPE_JSON = 100_000

    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        // Not half of a character that takes two.
        val end = if (s[max - 1].isHighSurrogate()) max - 1 else max
        return s.substring(0, end)
    }

    fun label(s: String): String = clip(s, LABEL)
    fun labelOrNull(s: String?): String? = s?.let { clip(it, LABEL) }
    fun note(s: String): String = clip(s, NOTE)
    fun words(list: List<String>): List<String> =
        if (list.size <= LIST && list.all { it.length <= LABEL }) list else list.take(LIST).map { label(it) }
}

@Serializable
data class BackupApp(
    val platform: String = "android",
    val appVersion: String = "",
    val packageName: String = "",
    val dbVersion: Int = 0,
)

@Serializable
data class BackupOptions(
    val includesPhotos: Boolean = false,
    val includesAnalyses: Boolean = false,
)

/** Null = the file did not carry that key, so importing leaves this phone's value alone. */
@Serializable
data class SettingsDto(
    val tempUnit: String? = null,
    val weightUnit: String? = null,
    val darkMode: String? = null,
    val haptics: Boolean? = null,
    val soundCues: Boolean? = null,
    val keepScreenOn: Boolean? = null,
    val autoAdvanceSteps: Boolean? = null,
    val name: String? = null,
    val ownedBrewerIds: List<String>? = null,
    val favouriteRoasterIds: List<String>? = null,
    val homeArea: String? = null,
    val defaultWaterId: String? = null,
    val defaultBeanUid: String? = null,
    val defaultGrinderUid: String? = null,
    val lastRecipeId: String? = null,
    val onboardingDone: Boolean? = null,
    // A null uid means "no default" when the key was in the file, and "say nothing" when it was not.
    @Transient val hasDefaultBean: Boolean = false,
    @Transient val hasDefaultGrinder: Boolean = false,
)

@Serializable
data class AiProviderDto(
    val id: String,
    /** Only for "custom". */
    val baseUrl: String? = null,
    val model: String = "",
    /** The user's answer for a model the catalog does not know. */
    val vision: Boolean? = null,
)

/** Which provider and model. Never a key, nor anything derived from one. */
@Serializable
data class AiDto(
    val activeProvider: String? = null,
    val sendPhotos: Boolean = true,
    val providers: List<AiProviderDto> = emptyList(),
)

@Serializable
data class BeanDto(
    val uid: String,
    val name: String,
    val roaster: String = "",
    val roasterId: String? = null,
    val origin: String = "",
    val originId: String? = null,
    val region: String = "",
    val farm: String = "",
    val producer: String = "",
    val washingStation: String = "",
    val lotTypeId: String? = null,
    val speciesId: String? = null,
    val varietalIds: List<String> = emptyList(),
    val varietal: String = "",
    /** "" = not recorded. */
    val processId: String = "",
    val isDecaf: Boolean = false,
    val decafMethodId: String? = null,
    /** "" = not recorded. */
    val roastLevelId: String = "",
    val roastIntentId: String? = null,
    /** "1700" or "1700-1900". */
    val altitudeM: String = "",
    val harvest: String = "",
    val roastedOn: String? = null,
    val openedOn: String? = null,
    val restDays: Int? = null,
    val bagSizeG: Double? = null,
    val priceAmount: Double? = null,
    val currency: String = "",
    /** 1.x free text. */
    val priceText: String = "",
    val tastingNotes: String = "",
    val notes: String = "",
    val isArchived: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class GrinderSettingDto(
    /** clicks | numbers | stepless | microns */
    val style: String = "clicks",
    val min: Double = 0.0,
    val max: Double = 40.0,
    val step: Double = 1.0,
    val lowerIsFiner: Boolean = true,
    /** null = unknown (Room stores 0). */
    val clicksPerRotation: Int? = null,
    val micronsPerStep: Double? = null,
    /** "" | "plain" | "turns" | "turns-dot" | "number-tick:10" */
    val dialFormat: String = "",
)

@Serializable
data class GrinderDto(
    val uid: String,
    val catalogId: String? = null,
    val brand: String = "",
    val model: String = "",
    /** conical | flat | ghost | blade | null */
    val burr: String? = null,
    val burrSizeMm: String = "",
    val setting: GrinderSettingDto = GrinderSettingDto(),
    val defaultFilterSetting: Double? = null,
    val defaultEspressoSetting: Double? = null,
    val notes: String = "",
    val isDefault: Boolean = false,
    val isArchived: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class BedDto(
    val shape: String? = null,
    val surface: String? = null,
    val channelling: String? = null,
    val notes: String = "",
)

@Serializable
data class TasteDto(
    val acidity: Int = 5,
    val sweetness: Int = 5,
    val bitterness: Int = 5,
    val body: Int = 5,
    val balance: Int = 5,
    val aftertaste: Int = 5,
    val clarity: Int = 5,
)

@Serializable
data class BrewDto(
    val uid: String,
    val startedAt: String,
    val updatedAt: String? = null,
    val recipeId: String = "",
    val recipeName: String = "",
    val recipeAuthor: String = "",
    val brewerId: String? = null,
    val brewerLabel: String = "",
    val beanUid: String? = null,
    val beanLabel: String = "",
    val daysOffRoast: Int? = null,
    val grinderUid: String? = null,
    val grinderLabel: String = "",
    val grindSetting: String = "",
    /** Total clicks from zero on click grinders. */
    val grindClicks: Int? = null,
    /** The dial number on numbered and stepless grinders. */
    val grindValue: Double? = null,
    val doseG: Double = 0.0,
    val waterG: Double = 0.0,
    /** Always °C. */
    val tempC: Int = 0,
    val waterId: String? = null,
    val totalTimeSec: Int = 0,
    val drawdownSec: Int? = null,
    val beverageG: Double? = null,
    val tdsPercent: Double? = null,
    val extractionYield: Double? = null,
    val flow: String? = null,
    val bed: BedDto = BedDto(),
    val taste: TasteDto = TasteDto(),
    val overall: Int = 0,
    val descriptors: List<String> = emptyList(),
    val faults: List<String> = emptyList(),
    val notes: String = "",
    val nextTime: String = "",
    val isFavourite: Boolean = false,
    val sharedAt: String? = null,
)

@Serializable
data class AnalysisDto(
    val uid: String,
    /** brew | trend */
    val kind: String,
    val brewUid: String? = null,
    val createdAt: String,
    /** android | web */
    val origin: String = "android",
    val promptVersion: Int = 1,
    val inputHash: String = "",
    val brewCount: Int = 0,
    val newestBrewAt: String? = null,
    val providerId: String = "custom",
    val model: String = "",
    val host: String = "",
    val rawText: String = "",
    /** The parsed result object, or null when the reply did not parse. */
    val result: JsonElement? = null,
    val flags: List<JsonElement> = emptyList(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val costMicroUsd: Long? = null,
    val latencyMs: Long = 0,
    val photoCount: Int = 0,
)

@Serializable
data class PhotoDto(
    val uid: String,
    val brewUid: String? = null,
    val beanUid: String? = null,
    /** bed | cup | bag | other */
    val kind: String = "other",
    val takenAt: String,
    val createdAt: String,
    val caption: String = "",
    val sendToAi: Boolean = true,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val mediaType: String = "image/jpeg",
    /** Base64, no line breaks; null when the backup was saved without photos. */
    val data: String? = null,
)

@Serializable
data class BackupDoc(
    val format: String = Backup.FORMAT,
    val version: Int = Backup.VERSION,
    val exportedAt: String = "",
    val app: BackupApp = BackupApp(),
    val options: BackupOptions = BackupOptions(),
    val settings: SettingsDto? = null,
    val ai: AiDto? = null,
    val beans: List<BeanDto> = emptyList(),
    val grinders: List<GrinderDto> = emptyList(),
    val brews: List<BrewDto> = emptyList(),
    /** Each exactly an entry of shared/recipes.json plus "updatedAt". */
    val recipes: List<JsonObject> = emptyList(),
    val analyses: List<AnalysisDto> = emptyList(),
    val photos: List<PhotoDto> = emptyList(),
)

data class Counts(
    val brews: Int = 0,
    val beans: Int = 0,
    val grinders: Int = 0,
    val recipes: Int = 0,
    val analyses: Int = 0,
    val photos: Int = 0,
) {
    val total: Int get() = brews + beans + grinders + recipes + analyses + photos

    companion object {
        fun of(doc: BackupDoc): Counts = Counts(
            brews = doc.brews.size,
            beans = doc.beans.size,
            grinders = doc.grinders.size,
            recipes = doc.recipes.size,
            analyses = doc.analyses.size,
            photos = doc.photos.size,
        )
    }
}
