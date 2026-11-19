package com.laksh.coffee.data.catalog

import android.content.res.AssetManager
import com.laksh.coffee.domain.catalog.CatalogSource
import java.io.FileNotFoundException
import java.io.InputStream

/** shared/ is packaged as APK assets (app/build.gradle.kts), so paths match the repo layout. */
class AssetCatalogSource(private val assets: AssetManager) : CatalogSource {
    override fun open(path: String): InputStream? =
        try {
            assets.open(path)
        } catch (_: FileNotFoundException) {
            null
        }
}
