package com.laksh.coffee

import android.content.Intent

/** Release twin of the debug-only adb hooks: both are no-ops so nothing ships. */
object DebugTools {

    fun startRoute(intent: Intent?): String? = null

    fun isDriven(intent: Intent?): Boolean = false

    suspend fun seedIfRequested(intent: Intent?, app: CoffeeApp) = Unit
}
