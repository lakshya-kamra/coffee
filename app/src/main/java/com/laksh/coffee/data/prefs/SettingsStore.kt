package com.laksh.coffee.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.laksh.coffee.domain.Temperatures
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "coffee_settings")

data class Settings(
    val lastRecipeId: String = "",
    val defaultBeanId: Long = 0L,
    val defaultGrinderId: Long = 0L,
    val haptics: Boolean = true,
    val soundCues: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoAdvanceSteps: Boolean = true,
    val darkMode: String = "system",
    val name: String = "",
    /** "c" or "f". Temperatures convert fully. */
    val tempUnit: String = "c",
    /** "g" or "oz". Brew targets stay in grams (scales read grams); bag sizes and totals convert. */
    val weightUnit: String = "g",
    val onboardingDone: Boolean = false,
    /** An existing user skipped onboarding automatically; Home offers a "Personalise" card instead. */
    val onboardingAuto: Boolean = false,
    val personaliseDismissed: Boolean = false,
    /** brewers.json ids. */
    val ownedBrewerIds: Set<String> = emptySet(),
    /** roasters.json ids. */
    val favouriteRoasterIds: Set<String> = emptySet(),
    /** An `area` value from roasters/origins, e.g. "Europe". */
    val homeArea: String = "",
    /** water.json id. */
    val defaultWaterId: String = "",
    /** One-off notices waiting for the user, e.g. "grinder-scale:<uid>". */
    val pendingNotices: Set<String> = emptySet(),
    val startupTasksVersion: Int = 0,
    // ---- v4 (backup 2.1)
    /** Onboarding's "How do you make coffee now?": "" (not asked) | "new" | "recipes" | "tinker". */
    val experience: String = "",
    /** false: brewing amounts show in tablespoons and ml (grams stay the stored truth). */
    val hasScale: Boolean = true,
    /** "quick" | "detailed". Fresh installs store "quick"; an install that had brews, "detailed" (StartupTasks). */
    val reviewMode: String = "quick",
    /** "I buy ground coffee": brews default to pre-ground. */
    val buysGround: Boolean = false,
    // ---- v4, this device only: never written to a backup
    /** The chime question has been answered. */
    val soundCuesAsked: Boolean = false,
    /** Large windows: the sidebar folded to the rail. */
    val sidebarCollapsed: Boolean = false,
    /** Log layout on wide windows: "list" | "table". */
    val logView: String = "list",
)

/** reviewMode for an install that never stored one: detailed for someone who already has brews. */
fun initialReviewMode(hasBrews: Boolean): String = if (hasBrews) "detailed" else "quick"

class SettingsStore(private val context: Context) {

    private object Keys {
        val LAST_RECIPE = stringPreferencesKey("last_recipe")
        val DEFAULT_BEAN = longPreferencesKey("default_bean")
        val DEFAULT_GRINDER = longPreferencesKey("default_grinder")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SOUND = booleanPreferencesKey("sound")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val NAME = stringPreferencesKey("name")
        val TEMP_UNIT = stringPreferencesKey("temp_unit")
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ONBOARDING_AUTO = booleanPreferencesKey("onboarding_auto")
        val PERSONALISE_DISMISSED = booleanPreferencesKey("personalise_dismissed")
        val OWNED_BREWERS = stringSetPreferencesKey("owned_brewers")
        val FAV_ROASTERS = stringSetPreferencesKey("fav_roasters")
        val HOME_AREA = stringPreferencesKey("home_area")
        val DEFAULT_WATER = stringPreferencesKey("default_water")
        val PENDING_NOTICES = stringSetPreferencesKey("pending_notices")
        val STARTUP_TASKS_VERSION = intPreferencesKey("startup_tasks_version")
        val EXPERIENCE = stringPreferencesKey("experience")
        val HAS_SCALE = booleanPreferencesKey("has_scale")
        val REVIEW_MODE = stringPreferencesKey("review_mode")
        val BUYS_GROUND = booleanPreferencesKey("buys_ground")
        val SOUND_CUES_ASKED = booleanPreferencesKey("sound_cues_asked")
        val SIDEBAR_COLLAPSED = booleanPreferencesKey("sidebar_collapsed")
        val LOG_VIEW = stringPreferencesKey("log_view")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p: Preferences -> settings(p) }

    private fun settings(p: Preferences): Settings =
        Settings(
            lastRecipeId = p[Keys.LAST_RECIPE] ?: "",
            defaultBeanId = p[Keys.DEFAULT_BEAN] ?: 0L,
            defaultGrinderId = p[Keys.DEFAULT_GRINDER] ?: 0L,
            haptics = p[Keys.HAPTICS] ?: true,
            soundCues = p[Keys.SOUND] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            autoAdvanceSteps = p[Keys.AUTO_ADVANCE] ?: true,
            darkMode = p[Keys.DARK_MODE] ?: "system",
            name = p[Keys.NAME] ?: "",
            // A fresh install takes °F where the region uses it (v4 §4.15); StartupTasks pins it.
            tempUnit = p[Keys.TEMP_UNIT] ?: Temperatures.defaultUnit(Locale.getDefault()),
            weightUnit = p[Keys.WEIGHT_UNIT] ?: "g",
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            onboardingAuto = p[Keys.ONBOARDING_AUTO] ?: false,
            personaliseDismissed = p[Keys.PERSONALISE_DISMISSED] ?: false,
            ownedBrewerIds = p[Keys.OWNED_BREWERS] ?: emptySet(),
            favouriteRoasterIds = p[Keys.FAV_ROASTERS] ?: emptySet(),
            homeArea = p[Keys.HOME_AREA] ?: "",
            defaultWaterId = p[Keys.DEFAULT_WATER] ?: "",
            pendingNotices = p[Keys.PENDING_NOTICES] ?: emptySet(),
            startupTasksVersion = p[Keys.STARTUP_TASKS_VERSION] ?: 0,
            experience = p[Keys.EXPERIENCE] ?: "",
            hasScale = p[Keys.HAS_SCALE] ?: true,
            reviewMode = p[Keys.REVIEW_MODE] ?: "quick",
            buysGround = p[Keys.BUYS_GROUND] ?: false,
            soundCuesAsked = p[Keys.SOUND_CUES_ASKED] ?: false,
            sidebarCollapsed = p[Keys.SIDEBAR_COLLAPSED] ?: false,
            logView = p[Keys.LOG_VIEW] ?: "list",
        )

    suspend fun setLastRecipe(id: String) = context.dataStore.edit { it[Keys.LAST_RECIPE] = id }
    suspend fun setDefaultBean(id: Long) = context.dataStore.edit { it[Keys.DEFAULT_BEAN] = id }
    suspend fun setDefaultGrinder(id: Long) = context.dataStore.edit { it[Keys.DEFAULT_GRINDER] = id }
    suspend fun setHaptics(on: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = on }
    suspend fun setSound(on: Boolean) = context.dataStore.edit { it[Keys.SOUND] = on }
    suspend fun setKeepScreenOn(on: Boolean) = context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = on }
    suspend fun setAutoAdvance(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_ADVANCE] = on }
    suspend fun setDarkMode(mode: String) = context.dataStore.edit { it[Keys.DARK_MODE] = mode }
    suspend fun setName(name: String) = context.dataStore.edit { it[Keys.NAME] = name }
    suspend fun setTempUnit(unit: String) = context.dataStore.edit { it[Keys.TEMP_UNIT] = unit }
    suspend fun setWeightUnit(unit: String) = context.dataStore.edit { it[Keys.WEIGHT_UNIT] = unit }
    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    suspend fun setOnboardingAuto(auto: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_AUTO] = auto }
    suspend fun setPersonaliseDismissed(dismissed: Boolean) =
        context.dataStore.edit { it[Keys.PERSONALISE_DISMISSED] = dismissed }
    suspend fun setOwnedBrewerIds(ids: Set<String>) = context.dataStore.edit { it[Keys.OWNED_BREWERS] = ids }
    suspend fun setFavouriteRoasterIds(ids: Set<String>) = context.dataStore.edit { it[Keys.FAV_ROASTERS] = ids }
    suspend fun setHomeArea(area: String) = context.dataStore.edit { it[Keys.HOME_AREA] = area }
    suspend fun setDefaultWaterId(id: String) = context.dataStore.edit { it[Keys.DEFAULT_WATER] = id }
    suspend fun setStartupTasksVersion(v: Int) = context.dataStore.edit { it[Keys.STARTUP_TASKS_VERSION] = v }
    suspend fun setExperience(value: String) = context.dataStore.edit { it[Keys.EXPERIENCE] = value }
    suspend fun setHasScale(on: Boolean) = context.dataStore.edit { it[Keys.HAS_SCALE] = on }
    suspend fun setReviewMode(mode: String) = context.dataStore.edit { it[Keys.REVIEW_MODE] = mode }
    suspend fun setBuysGround(on: Boolean) = context.dataStore.edit { it[Keys.BUYS_GROUND] = on }
    suspend fun setSoundCuesAsked(asked: Boolean) = context.dataStore.edit { it[Keys.SOUND_CUES_ASKED] = asked }
    suspend fun setSidebarCollapsed(collapsed: Boolean) = context.dataStore.edit { it[Keys.SIDEBAR_COLLAPSED] = collapsed }
    suspend fun setLogView(view: String) = context.dataStore.edit { it[Keys.LOG_VIEW] = view }

    /**
     * v4 defaults that must not move once someone has used the app: an existing install keeps
     * °C and every Review section open, a fresh one stores the region's unit and "quick".
     * Stores nothing for keys already set. [existing] = the install already has data.
     */
    suspend fun pinV4Defaults(existing: Boolean, hasBrews: Boolean) = context.dataStore.edit { p ->
        if (p[Keys.TEMP_UNIT] == null) p[Keys.TEMP_UNIT] = if (existing) "c" else Temperatures.defaultUnit(Locale.getDefault())
        if (p[Keys.REVIEW_MODE] == null) p[Keys.REVIEW_MODE] = initialReviewMode(hasBrews)
    }

    /**
     * Backup restore: every setting a backup can carry, written in one edit. [change] gets the
     * settings as they are inside that edit, so nothing changed a moment earlier is lost.
     */
    suspend fun restore(change: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = change(settings(p))
            p[Keys.LAST_RECIPE] = s.lastRecipeId
            p[Keys.DEFAULT_BEAN] = s.defaultBeanId
            p[Keys.DEFAULT_GRINDER] = s.defaultGrinderId
            p[Keys.HAPTICS] = s.haptics
            p[Keys.SOUND] = s.soundCues
            p[Keys.KEEP_SCREEN_ON] = s.keepScreenOn
            p[Keys.AUTO_ADVANCE] = s.autoAdvanceSteps
            p[Keys.DARK_MODE] = s.darkMode
            p[Keys.NAME] = s.name
            p[Keys.TEMP_UNIT] = s.tempUnit
            p[Keys.WEIGHT_UNIT] = s.weightUnit
            p[Keys.ONBOARDING_DONE] = s.onboardingDone
            p[Keys.ONBOARDING_AUTO] = s.onboardingAuto
            p[Keys.OWNED_BREWERS] = s.ownedBrewerIds
            p[Keys.FAV_ROASTERS] = s.favouriteRoasterIds
            p[Keys.HOME_AREA] = s.homeArea
            p[Keys.DEFAULT_WATER] = s.defaultWaterId
            p[Keys.EXPERIENCE] = s.experience
            p[Keys.HAS_SCALE] = s.hasScale
            p[Keys.REVIEW_MODE] = s.reviewMode
            p[Keys.BUYS_GROUND] = s.buysGround
        }
    }

    suspend fun addNotice(id: String) = context.dataStore.edit {
        it[Keys.PENDING_NOTICES] = (it[Keys.PENDING_NOTICES] ?: emptySet()) + id
    }

    suspend fun removeNotice(id: String) = context.dataStore.edit {
        it[Keys.PENDING_NOTICES] = (it[Keys.PENDING_NOTICES] ?: emptySet()) - id
    }
}
