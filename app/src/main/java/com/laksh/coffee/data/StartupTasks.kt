package com.laksh.coffee.data

import com.laksh.coffee.CoffeeApp
import com.laksh.coffee.data.catalog.CatalogLinker
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.coroutines.flow.first

/**
 * One-off work after an install or upgrade, run once per process from CoffeeApp's preload
 * coroutine. Idempotent: the one-off steps are guarded by
 * [com.laksh.coffee.data.prefs.Settings.startupTasksVersion], the rest check before they write.
 */
object StartupTasks {

    const val VERSION = 1

    suspend fun run(app: CoffeeApp) {
        val settings = app.settings
        val s = settings.settings.first()
        if (s.startupTasksVersion < 1) {
            linkAll(app)
            settings.setStartupTasksVersion(1)
        }
        // Every start, not once: someone with data is never pushed into setup, and Home offers
        // "Make it yours" instead. That is an upgraded 1.x install, and also a new one whose
        // setup was left half-way after it had saved a grinder (the gate will not reopen it).
        if (!s.onboardingDone && !app.repository.isEmpty()) {
            settings.setOnboardingDone(true)
            settings.setOnboardingAuto(true)
        }
        // Every start: photo files left without a row (a crash mid-add, a deleted brew) are removed.
        runCatching { app.photos.sweep() }
    }

    /** Links grinders and beans typed before the catalog existed. Only ids are written. */
    suspend fun linkAll(app: CoffeeApp) {
        val repo = app.repository
        val grinders = Catalog.grinders
        repo.allGrinders().filter { it.catalogId == null }.forEach { row ->
            val match = CatalogLinker.matchGrinder(row, grinders) ?: return@forEach
            repo.linkGrinder(row.copy(catalogId = match.id))
            if (CatalogLinker.scaleDiffers(row, match)) app.settings.addNotice("grinder-scale:${row.uid}")
        }
        val roasters = Catalog.roasters
        val origins = Catalog.origins
        repo.allBeans().filter { it.roasterId == null || it.originId == null }.forEach { bean ->
            CatalogLinker.linkBean(bean, roasters, origins)?.let { repo.linkBean(it) }
        }
    }
}
