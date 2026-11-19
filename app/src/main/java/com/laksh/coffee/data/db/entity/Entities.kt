package com.laksh.coffee.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.laksh.coffee.core.counted
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.domain.grind.GrindScale

// --------------------------------------------------------------------- beans

// Process and roast level are catalog ids (shared/catalog/beans.json) since DB v4; labels come
// from Catalog.label(TermKind.Process, id). MIGRATION_3_4 mapped the 1.x enum names.

@Entity(tableName = "beans", indices = [Index(value = ["uid"], unique = true)])
data class BeanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Coffee name, e.g. "Kiamabara AA". */
    val name: String,
    /** Roaster or brand, e.g. "Square Mile". */
    val roaster: String = "",
    val origin: String = "",
    val region: String = "",
    /** Free text: custom varietal names. Catalog varietals live in [varietalIds]. */
    val varietal: String = "",
    /** beans.json process id; "" = not recorded. */
    @ColumnInfo(name = "process") val processId: String = "",
    /** beans.json roast level id; "" = not recorded. */
    @ColumnInfo(name = "roastLevel") val roastLevelId: String = "",
    /** Epoch day, so "days off roast" is a subtraction. */
    val roastedOnEpochDay: Long? = null,
    val openedOnEpochDay: Long? = null,
    val bagSizeG: Double? = null,
    val price: String = "",
    val altitudeMasl: String = "",
    val tastingNotes: String = "",
    val notes: String = "",
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // ---- v4. Every NOT NULL column has a default so a fresh install and a migrated database match.
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** roasters.json id; [roaster] stays the display or custom name. */
    val roasterId: String? = null,
    /** origins.json id; [origin] stays the display or custom country. [region] is the growing region. */
    val originId: String? = null,
    /** Farm or estate. */
    @ColumnInfo(defaultValue = "") val farm: String = "",
    @ColumnInfo(defaultValue = "") val producer: String = "",
    @ColumnInfo(defaultValue = "") val washingStation: String = "",
    val lotTypeId: String? = null,
    val speciesId: String? = null,
    @ColumnInfo(defaultValue = "") val varietalIds: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "0") val isDecaf: Boolean = false,
    val decafMethodId: String? = null,
    val roastIntentId: String? = null,
    /** Free text, e.g. "2025/26". */
    @ColumnInfo(defaultValue = "") val harvest: String = "",
    /** The rest the roaster suggests before opening, in days. */
    val restDays: Int? = null,
    /** [price] stays for 1.x free-text values. */
    val priceAmount: Double? = null,
    /** ISO 4217, e.g. "GBP". */
    @ColumnInfo(defaultValue = "") val currency: String = "",
) {
    // Parts are trimmed for the label only, so a value typed with a trailing space never reads "Roaster  — Name".
    val display: String get() = if (roaster.isBlank()) name.trim() else "${roaster.trim()} — ${name.trim()}"
}

// ------------------------------------------------------------------ grinders

enum class BurrType(val label: String) {
    Conical("Conical"), Flat("Flat"), Ghost("Ghost burr"), Blade("Blade"), Unknown("Unknown"),
}

enum class SettingStyle(val label: String, val unit: String) {
    Clicks("Clicks", "clicks"),
    Numbers("Numbered dial", ""),
    Microns("Microns", "µm"),
    Stepless("Stepless / degrees", "°"),
}

@Entity(tableName = "grinders", indices = [Index(value = ["uid"], unique = true)])
data class GrinderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String,
    val model: String,
    val burrType: BurrType = BurrType.Unknown,
    val burrSizeMm: String = "",
    val settingStyle: SettingStyle = SettingStyle.Clicks,
    /** Clicks in one full turn of the dial, so 32 can be shown as "3 turns + 2". 0 = unknown. */
    val clicksPerRotation: Int = 0,
    /** Optional, from the manual. Lets a click count be converted to a burr gap. */
    val micronsPerClick: Double? = null,
    /**
     * True for anything zeroed at burr contact and dialled outward (every hand grinder):
     * fewer clicks is finer. False for dials numbered the other way round.
     */
    val lowerIsFiner: Boolean = true,
    /** Usable range shown on the dial control. */
    val minSetting: Double = 0.0,
    val maxSetting: Double = 40.0,
    val step: Double = 1.0,
    /** Your go-to filter setting, prefilled when starting a brew. */
    val defaultFilterSetting: Double? = null,
    val defaultEspressoSetting: Double? = null,
    val notes: String = "",
    val isDefault: Boolean = false,
    val isArchived: Boolean = false,
    // ---- v4
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** grinders.json id. Linking never changes the numbers above without the user's OK. */
    val catalogId: String? = null,
    /** How the dial reads; "" = auto. See domain/grind/DialFormat. */
    @ColumnInfo(defaultValue = "") val dialFormat: String = "",
) {
    val display: String get() = listOf(brand, model).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    val usesClicks: Boolean get() = settingStyle == SettingStyle.Clicks

    /** This row's scale, with the catalog's ranges and help when linked. */
    fun scale(): GrindScale = GrindScale.of(this)

    /**
     * The text snapshot stored with a brew: "75 clicks (7 turns + 5)" on a 1.x row,
     * "75 clicks (dial 7.5)" on a numbered dial, "32 clicks" when the dial adds nothing.
     */
    fun describeClicks(clicks: Int): String {
        val base = counted(clicks, "click")
        val s = scale()
        val words = when (s.dial) {
            is DialFormat.TurnsDotClicks, is DialFormat.TurnsAndClicks ->
                DialFormat.turnsPlus(clicks / clicksPerRotation, (clicks % clicksPerRotation).toString())
            is DialFormat.NumberTick -> s.reading(clicks.toDouble())?.let { "dial $it" }
            DialFormat.Plain -> null
        }
        return if (words == null) base else "$base ($words)"
    }

    /** Which way to turn for a finer grind, in words a person can act on. */
    val finerDirection: String get() = scale().finerWords
}

// ----------------------------------------------------------------- brew logs

@Entity(
    tableName = "brew_logs",
    indices = [Index("startedAt"), Index("beanId"), Index("recipeId"), Index(value = ["uid"], unique = true)],
)
data class BrewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),

    // --- what you brewed with (snapshotted so history survives edits/deletes)
    val recipeId: String = "",
    val recipeName: String = "",
    val recipeAuthor: String = "",
    val brewer: String = "",

    val beanId: Long? = null,
    val beanLabel: String = "",
    val daysOffRoast: Long? = null,

    val grinderId: Long? = null,
    val grinderLabel: String = "",
    val grindSetting: String = "",
    /** Machine-readable grind position for click grinders. Null when the grinder uses a dial. */
    val grindClicks: Int? = null,

    val doseG: Double = 0.0,
    val waterG: Double = 0.0,
    val tempC: Int = 0,

    // --- what actually happened
    val totalTimeSec: Int = 0,
    /** Seconds from the last pour finishing to the bed being exposed. */
    val drawdownSec: Int? = null,
    val beverageG: Double? = null,
    val tdsPercent: Double? = null,
    @ColumnInfo(name = "extractionYield") val extractionYield: Double? = null,
    val flowFeel: FlowFeel = FlowFeel.Unset,

    // --- bed analysis
    val bedShape: BedShape = BedShape.Unset,
    val sludge: Sludge = Sludge.Unset,
    val channeling: Channeling = Channeling.Unset,
    val bedNotes: String = "",

    // --- taste
    @Embedded(prefix = "taste_") val taste: TasteProfile = TasteProfile(),
    val overall: Int = 0,
    val descriptors: List<String> = emptyList(),
    val faults: List<String> = emptyList(),
    val notes: String = "",
    /** The one thing you will change next time. */
    val nextTime: String = "",

    val isFavourite: Boolean = false,
    /** Set once this brew has been bundled into a report for Claude. */
    val sharedAt: Long? = null,

    // ---- v4
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** brewers.json id; [brewer] stays the label snapshot (same pattern as recipeId/recipeName). */
    val brewerId: String? = null,
    /** The dial number for numbered and stepless grinders. Click grinders use [grindClicks]. */
    val grindValue: Double? = null,
    /** water.json id, snapshotted from settings at brew time. */
    val waterId: String? = null,
)

// ------------------------------------------------------- user-saved recipes

/** A user-created or user-edited recipe, stored as serialized JSON of [com.laksh.coffee.domain.Recipe]. */
@Entity(tableName = "saved_recipes")
data class SavedRecipeEntity(
    @PrimaryKey val id: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
