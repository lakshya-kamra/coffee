package com.laksh.coffee.domain.catalog

import java.io.InputStream

/** Where the shared JSON comes from: APK assets on the phone, the classpath in JVM tests. */
interface CatalogSource {
    /** Opens e.g. "catalog/grinders.json"; null when the file is not there. */
    fun open(path: String): InputStream?
}

/** JVM tests: app/build.gradle.kts adds shared/ to the test resources. */
class ClasspathCatalogSource : CatalogSource {
    override fun open(path: String): InputStream? = javaClass.classLoader?.getResourceAsStream(path)
}
