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
)

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
            tempUnit = p[Keys.TEMP_UNIT] ?: "c",
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
        }
    }

    suspend fun addNotice(id: String) = context.dataStore.edit {
        it[Keys.PENDING_NOTICES] = (it[Keys.PENDING_NOTICES] ?: emptySet()) + id
    }

    suspend fun removeNotice(id: String) = context.dataStore.edit {
        it[Keys.PENDING_NOTICES] = (it[Keys.PENDING_NOTICES] ?: emptySet()) - id
    }
}
